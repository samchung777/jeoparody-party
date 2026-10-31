package party.jeoparody.research;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Decides what to research, and when. Called when a game ends (and from the library page): it queues
 * <ul>
 *   <li>stale categories: every clue already shown {@code staleThreshold} times (2 by default), and</li>
 *   <li>new player and host suggestions, and</li>
 *   <li>a first fan-wiki pass for categories with an allow-listed fan wiki,</li>
 * </ul>
 * then starts the research worker if anything is waiting. Planning is quick SQL on the game server; the slow part
 * (reading wikis, calling Gemini) happens in the worker.
 */
@Service
public class ResearchPlanner {

    private static final Logger log = LoggerFactory.getLogger(ResearchPlanner.class);

    private final ResearchRepository repository;
    private final ResearchLauncher launcher;
    private final ResearchProperties props;
    private final FanWikiClient fanWikis;

    public ResearchPlanner(ResearchRepository repository, ResearchLauncher launcher, ResearchProperties props,
            FanWikiClient fanWikis) {
        this.repository = repository;
        this.launcher = launcher;
        this.props = props;
        this.fanWikis = fanWikis;
    }

    /**
     * On start-up, plan once, so newly listed fan wikis (and anything left over) are researched without waiting
     * for a game to end. Skipped inside the research worker itself (app.startup-jobs=false).
     */
    @EventListener(value = ApplicationReadyEvent.class,
            condition = "@environment.getProperty('app.startup-jobs', 'true') == 'true'")
    public void onStartup() {
        if (props.enabled()) {
            afterGame(null);
        }
    }

    /** What a planning pass queued. */
    public record Plan(int staleCategories, int suggestions, int fanWikiPasses, int waiting, String runsIn) {
    }

    /** Fan-wiki categories queued per planning pass (each is one fan wiki read and one Gemini call). */
    static final int FAN_WIKI_BATCH = 50;

    /** Called by GameService when a game reaches Game Over. Never throws: a game must never fail because of this. */
    public Plan afterGame(String gameCode) {
        try {
            return plan(gameCode);
        } catch (RuntimeException e) {
            log.warn("Research planning after game {} failed: {}", gameCode, e.getMessage());
            return new Plan(0, 0, 0, 0, launcher.describe());
        }
    }

    /** Queues stale categories and new suggestions, then starts the worker if there is any queued work. */
    public Plan plan(String gameCode) {
        int stale = 0;
        for (ResearchRepository.StaleCategory c : repository.staleCategories(props.staleThreshold(), props.cooldown(),
                props.maxTasksPerPlan())) {
            repository.queue(ResearchTask.STALE, c.keywordId(), c.name(), gameCode);
            stale++;
        }
        int suggestions = 0;
        List<TopicSuggestion> fresh = repository.newSuggestions(props.maxTasksPerPlan());
        for (TopicSuggestion s : fresh) {
            if (repository.alreadyHandled(s.topic())) {
                repository.linkSuggestion(s.id(), "rejected", null); // a duplicate of one already researched
                continue;
            }
            long task = repository.queue(ResearchTask.SUGGESTION, null, s.topic(), s.gameCode());
            repository.linkSuggestion(s.id(), "queued", task);
            suggestions++;
        }
        int fanWiki = 0;
        for (ResearchRepository.StaleCategory c : repository.fanWikiCandidates(fanWikis.wikis().entries().keySet(),
                props.cooldown(), FAN_WIKI_BATCH)) {
            repository.queue(ResearchTask.FAN_WIKI, c.keywordId(), c.name(), gameCode);
            fanWiki++;
        }
        int waiting = repository.countQueued();
        if (waiting > 0) {
            log.info("Research after game {}: {} stale categories, {} suggestions and {} fan-wiki categories queued;"
                    + " {} waiting, running in {}", gameCode, stale, suggestions, fanWiki, waiting,
                    launcher.describe());
            launcher.launch();
        }
        return new Plan(stale, suggestions, fanWiki, waiting, launcher.describe());
    }

    /** Queues topics typed on the library page. */
    public Plan queueManual(List<String> topics) {
        for (String t : topics) {
            repository.queue(ResearchTask.MANUAL, null, SuggestionService.clean(t), null);
        }
        return plan(null);
    }
}
