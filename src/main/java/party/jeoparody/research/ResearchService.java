package party.jeoparody.research;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import party.jeoparody.enrich.EnrichedClue;
import party.jeoparody.enrich.EnrichmentRepository;
import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.enrich.TransientEnrichmentException;
import party.jeoparody.review.ReviewPrompt;
import party.jeoparody.trivia.TriviaRepository;
import party.jeoparody.wiki.GeneratedClue;
import party.jeoparody.wiki.WikiClueGenerator;
import party.jeoparody.wiki.WikiPage;
import party.jeoparody.wiki.WikiRepository;
import party.jeoparody.wiki.WikiSource;
import party.jeoparody.wiki.WikiText;

/**
 * Does the research: for each queued task, reads the sources, has Gemini write verified clues, and stores them
 * under the task's keyword.
 *
 * Sources: a stale category uses its allow-listed fan wiki when there is one (fresh, deep material), otherwise
 * Wikipedia. A suggested topic uses Wikipedia first and adds the fan wiki's pages when one is allow-listed.
 * Where it runs: in the research worker (a Cloud Run Job) in production, or on a background thread locally.
 */
@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);
    static final String FAN_WIKI_SOURCE = "fanwiki";
    static final String WIKIPEDIA_SOURCE = "wikipedia";
    private static final int MAX_ATTEMPTS = 4;

    private final ResearchRepository repository;
    private final FanWikiClient fanWikis;
    private final WikiSource wikipedia;
    private final WikiClueGenerator generator;
    private final WikiRepository wikiRepository;
    private final TriviaRepository trivia;
    private final EnrichmentRepository enrichment;
    private final ResearchProperties props;

    public ResearchService(ResearchRepository repository, FanWikiClient fanWikis, WikiSource wikipedia,
            WikiClueGenerator generator, WikiRepository wikiRepository, TriviaRepository trivia,
            EnrichmentRepository enrichment, ResearchProperties props) {
        this.repository = repository;
        this.fanWikis = fanWikis;
        this.wikipedia = wikipedia;
        this.generator = generator;
        this.wikiRepository = wikiRepository;
        this.trivia = trivia;
        this.enrichment = enrichment;
        this.props = props;
    }

    /** Works through every queued task on the calling thread. Returns how many tasks it handled. */
    public int processQueue() {
        repository.requeueStuck(java.time.Duration.ofHours(2));
        int handled = 0;
        Optional<ResearchTask> next;
        while (!Thread.currentThread().isInterrupted() && (next = repository.claimNext()).isPresent()) {
            process(next.get());
            handled++;
        }
        return handled;
    }

    /** Researches one claimed task and records the outcome on its row (and on any linked suggestion). */
    void process(ResearchTask task) {
        try {
            WikiText.TopicSpec spec = specFor(task);
            List<String> used = new ArrayList<>();
            Optional<WikiPage> page = sources(task, spec, used);
            if (page.isEmpty()) {
                log.info("Research '{}': no allow-listed fan wiki or Wikipedia article found", task.topic());
                repository.finish(task.id(), "not_found", null, null, 0, null);
                return;
            }
            int added = store(spec, page.get(), String.join("+", used));
            repository.finish(task.id(), "done", String.join(", ", used), page.get().url(), added, null);
            log.info("Research '{}' ({}): {} new clues from {}", task.topic(), task.kind(), added, used);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.finish(task.id(), "queued", null, null, 0, "interrupted");
        } catch (Exception e) {
            log.warn("Research '{}' failed: {}", task.topic(), e.getMessage());
            repository.finish(task.id(), "failed", null, null, 0, e.getMessage());
        }
    }

    /**
     * What to research. A stale category reuses the Wikipedia topic line it came from (so "Naruto" keeps its
     * groups and exact page); anything else is parsed as a topic line.
     */
    WikiText.TopicSpec specFor(ResearchTask task) {
        if (ResearchTask.STALE.equals(task.kind())) {
            Optional<String> original = repository.wikiTopicFor(task.topic());
            if (original.isPresent()) {
                WikiText.TopicSpec s = WikiText.parseTopic(original.get());
                return new WikiText.TopicSpec(s.keyword(), s.pageQuery(), s.groups(), s.focus(), null);
            }
            return new WikiText.TopicSpec(task.topic(), task.topic(), List.of(), null, null);
        }
        return WikiText.parseTopic(task.topic());
    }

    /** The material to write from: one page, or the fan wiki and Wikipedia combined. Records which were used. */
    private Optional<WikiPage> sources(ResearchTask task, WikiText.TopicSpec spec, List<String> used)
            throws IOException, InterruptedException {
        Optional<WikiPage> fan = fanWikis.fetch(spec.keyword());
        if (ResearchTask.STALE.equals(task.kind()) && fan.isPresent()) {
            used.add(FAN_WIKI_SOURCE);
            return fan;
        }
        Optional<WikiPage> wiki = wikipedia.fetch(spec.pageQuery());
        if (wiki.isPresent() && fan.isPresent()) {
            used.add(WIKIPEDIA_SOURCE);
            used.add(FAN_WIKI_SOURCE);
            return Optional.of(merge(wiki.get(), fan.get(), props.maxArticleChars()));
        }
        if (wiki.isPresent()) {
            used.add(WIKIPEDIA_SOURCE);
            return wiki;
        }
        if (fan.isPresent()) {
            used.add(FAN_WIKI_SOURCE);
        }
        return fan;
    }

    /** Wikipedia first, then the fan wiki, within the budget. The Wikipedia page stays the source of record. */
    static WikiPage merge(WikiPage wiki, WikiPage fan, int maxChars) {
        String main = WikiText.truncateAtSentence(wiki.text(), maxChars * 6 / 10);
        String header = "\n\nFrom the fan wiki (\"" + fan.title() + "\"):\n";
        int remaining = Math.max(0, maxChars - main.length() - header.length());
        String text = remaining < 500 ? main : main + header + WikiText.truncateAtSentence(fan.text(), remaining);
        return new WikiPage(wiki.pageId(), wiki.title(), wiki.url(), text);
    }

    /** Has Gemini write clues from the page and stores the new ones. Returns how many were added. */
    private int store(WikiText.TopicSpec spec, WikiPage page, String source)
            throws IOException, InterruptedException {
        Set<String> existing = repository.existingAnswers(WikiText.keyword(spec.keyword()));
        int count = spec.count() != null ? spec.count() : props.cluesPerTask();
        List<GeneratedClue> clues = generateWithRetry(spec, page, count, existing);
        String categorySource = source.startsWith(FAN_WIKI_SOURCE) ? FAN_WIKI_SOURCE : WIKIPEDIA_SOURCE;
        long categoryId = trivia.upsertCategory(categorySource, (int) (page.pageId() & 0x7fffffff), page.title());
        int added = 0;
        for (GeneratedClue c : clues) {
            OptionalLong id = wikiRepository.insertClue(categoryId, c, page.url(), ReviewPrompt.VERSION);
            if (id.isPresent()) {
                enrichment.save(new EnrichedClue(id.getAsLong(), c.clue(), c.response(), c.keywords()));
                added++;
            }
        }
        return added;
    }

    private List<GeneratedClue> generateWithRetry(WikiText.TopicSpec spec, WikiPage page, int count,
            Set<String> existing) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return generator.generate(spec, page, count, existing);
            } catch (TransientEnrichmentException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long wait = EnrichmentService.backoffMillis(attempt);
                log.warn("Gemini busy for research '{}', retrying in {}s", spec.keyword(), wait / 1000);
                Thread.sleep(wait);
            }
        }
    }

    /** Which fan wikis are allow-listed (library page). */
    public FanWikis fanWikis() {
        return fanWikis.wikis();
    }
}
