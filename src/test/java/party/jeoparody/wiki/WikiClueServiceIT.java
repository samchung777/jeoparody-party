package party.jeoparody.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import party.jeoparody.game.GameRepository;
import party.jeoparody.game.KeywordOption;
import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

/** The whole topic -> article -> clues -> database flow, with Wikipedia and Gemini replaced by fakes. */
class WikiClueServiceIT extends IntegrationTest {

    @MockitoBean
    WikiSource source;
    @MockitoBean
    WikiClueGenerator generator;
    @Autowired
    WikiClueService service;
    @Autowired
    WikiRepository repository;
    @Autowired
    GameRepository games;

    private final WikiPage hamilton = new WikiPage(42, "Hamilton (musical)",
            "https://en.wikipedia.org/wiki/Hamilton_(musical)", "Hamilton is a musical by Lin-Manuel Miranda.");

    private static GeneratedClue clue(int i, String difficulty) {
        return new GeneratedClue(difficulty, "Hamilton clue number " + i, "Who is person " + i + "?", "Person " + i,
                "Quote proving fact number " + i, List.of("Hamilton", i % 2 == 0 ? "Act One" : "Act Two"));
    }

    @Test
    void topicsBecomeAPlayableCategory() throws Exception {
        when(source.fetch("Hamilton (musical)")).thenReturn(Optional.of(hamilton));
        when(source.fetch("Nonexistent Thing")).thenReturn(Optional.empty());
        when(generator.generate(any(), eq(hamilton), anyInt(), anySet())).thenReturn(List.of(
                clue(1, "easy"), clue(2, "easy"), clue(3, "medium"), clue(4, "hard"), clue(5, "hard"), clue(6, "hard")));

        assertEquals(List.of("Hamilton (musical)", "Nonexistent Thing"),
                service.queue("Hamilton (musical),\n  Nonexistent   Thing ; Hamilton (musical)"));
        service.processQueue();

        Map<String, WikiTopic> byTopic = byTopic();
        assertEquals("done", byTopic.get("Hamilton (musical)").status());
        assertEquals(6, byTopic.get("Hamilton (musical)").cluesAdded());
        assertEquals("Hamilton (musical)", byTopic.get("Hamilton (musical)").pageTitle());
        assertEquals("not_found", byTopic.get("Nonexistent Thing").status());

        // Stored with source, quote, Jeopardy wording and the topic keyword
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_url, source_quote, jeopardy_response FROM clue WHERE question = 'Hamilton clue number 1'");
        assertEquals("https://en.wikipedia.org/wiki/Hamilton_(musical)", row.get("source_url"));
        assertEquals("Quote proving fact number 1", row.get("source_quote"));
        assertEquals("Who is person 1?", row.get("jeopardy_response"));
        assertEquals("Hamilton (musical)", jdbc.queryForObject(
                "SELECT name FROM category WHERE source = 'wikipedia' AND source_id = 42", String.class));

        // ...and the topic is now a keyword the game can offer
        KeywordOption keyword = games.eligibleKeywords(5).stream().filter(k -> k.name().equals("Hamilton"))
                .findFirst().orElseThrow();
        assertEquals(6, keyword.clueCount());
    }

    @Test
    void identicalCluesAreNotStoredTwice() throws Exception {
        when(source.fetch("Hamilton (musical)")).thenReturn(Optional.of(hamilton));
        when(generator.generate(any(), eq(hamilton), anyInt(), anySet())).thenReturn(List.of(clue(1, "easy")));
        service.queue("Hamilton (musical)");
        service.processQueue();

        jdbc.update("UPDATE wiki_topic SET status = 'failed'"); // force a re-run of the same topic
        service.queue("Hamilton (musical)");
        service.processQueue();

        assertEquals(1, byTopic().get("Hamilton (musical)").cluesAdded(), "the re-run added nothing new");
        assertEquals(1, TestData.count(jdbc, "SELECT COUNT(*) FROM clue WHERE source_url IS NOT NULL"));
    }

    @Test
    void failuresAreRecordedAndCanBeRetried() throws Exception {
        when(source.fetch("Fire Emblem")).thenThrow(new IOException("Wikipedia API returned HTTP 503"));
        service.queue("Fire Emblem");
        service.processQueue();
        WikiTopic failed = byTopic().get("Fire Emblem");
        assertEquals("failed", failed.status());
        assertTrue(failed.error().contains("503"));

        service.queue("Fire Emblem");
        assertEquals("queued", byTopic().get("Fire Emblem").status(), "re-submitting retries a failed topic");
    }

    @Test
    void doneTopicsAreNotQueuedAgain() throws Exception {
        when(source.fetch("Hamilton (musical)")).thenReturn(Optional.of(hamilton));
        when(generator.generate(any(), eq(hamilton), anyInt(), anySet())).thenReturn(List.of());
        service.queue("Hamilton (musical)");
        service.processQueue();
        service.queue("Hamilton (musical)");
        assertEquals("done", byTopic().get("Hamilton (musical)").status());
    }

    private Map<String, WikiTopic> byTopic() {
        return repository.list(100).stream().collect(java.util.stream.Collectors.toMap(WikiTopic::topic, t -> t));
    }

    @Test
    void groupsBecomeExtraKeywordsAndOutdatedTopicsAreRegeneratedWithoutRepeats() throws Exception {
        when(source.fetch("Hamilton (musical)")).thenReturn(Optional.of(hamilton));
        when(generator.generate(any(), eq(hamilton), anyInt(), anySet())).thenAnswer(inv -> {
            WikiText.TopicSpec spec = inv.getArgument(0);
            return List.of(new GeneratedClue("easy", "Group clue", "Who is Eliza?", "Eliza", "q",
                    party.jeoparody.wiki.WikiCluePrompt.keywords(new com.fasterxml.jackson.databind.ObjectMapper()
                            .createArrayNode(), spec, "eliza")));
        });
        service.queue("Hamilton = Hamilton (musical) [Broadway Musicals] {6: the Schuyler sisters}");
        service.processQueue();

        assertEquals(List.of("Broadway Musicals", "Hamilton"), jdbc.queryForList("""
                SELECT k.name FROM keyword k JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id WHERE cl.question = 'Group clue' ORDER BY k.name""", String.class));
        verify(generator).generate(any(), eq(hamilton), eq(6), eq(java.util.Set.of()));

        // Pretend it was written with the old prompt: it is re-queued and regenerated, excluding "Eliza"
        jdbc.update("UPDATE wiki_topic SET prompt_version = 1");
        assertEquals(1, repository.requeueOutdated(WikiCluePrompt.VERSION));
        service.processQueue();
        verify(generator).generate(any(), eq(hamilton), eq(6), eq(java.util.Set.of("eliza")));
        assertEquals(WikiCluePrompt.VERSION, jdbc.queryForObject("SELECT prompt_version FROM wiki_topic", Integer.class));
    }
}
