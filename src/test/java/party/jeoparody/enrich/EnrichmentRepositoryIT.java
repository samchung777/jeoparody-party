package party.jeoparody.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

/**
 * Integration test (Failsafe, `*IT`): runs against real MySQL (docker-compose locally, the MySQL sidecar in CircleCI).
 * Checks the enrichment queue SQL: saving results, re-tagging, prompt versions, attempt limits, titles.
 */
class EnrichmentRepositoryIT extends IntegrationTest {

    @Autowired
    EnrichmentRepository repository;

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void savingAConversionStoresWordingAndKeywordsAndClearsItFromThePendingQueue() {
        long category = TestData.category(jdbc, "Film");
        long clue = TestData.rawClue(jdbc, category, "easy", "What 1985 film features a DeLorean?", "Back to the Future");
        assertEquals(1, repository.countPending(MAX_ATTEMPTS));
        List<ClueToEnrich> pending = repository.findPending(10, MAX_ATTEMPTS);
        assertEquals("Film", pending.get(0).category());

        repository.save(new EnrichedClue(clue, "This 1985 film sent Marty McFly back in time",
                "What is Back to the Future?", List.of("80s Movies", "Time Travel")));

        assertEquals(0, repository.countPending(MAX_ATTEMPTS));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT jeopardy_clue, jeopardy_response, enrich_version, feedback_note FROM clue WHERE id = ?", clue);
        assertEquals("What is Back to the Future?", row.get("jeopardy_response"));
        assertEquals(EnrichmentPrompt.VERSION, ((Number) row.get("enrich_version")).intValue());
        assertNull(row.get("feedback_note"));
        assertEquals(2, TestData.count(jdbc, "SELECT COUNT(*) FROM clue_keyword WHERE clue_id = ?", clue));
    }

    @Test
    void retaggingReplacesKeywordsAndUnusedOnesAreCleanedUp() {
        long category = TestData.category(jdbc, "Film");
        long clue = TestData.rawClue(jdbc, category, "easy", "Q?", "A");
        repository.save(new EnrichedClue(clue, "c", "What is A?", List.of("Old Keyword")));
        repository.save(new EnrichedClue(clue, "c", "What is A?", List.of("New Keyword")));

        assertEquals(1, repository.deleteUnusedKeywords());
        assertEquals(List.of("New Keyword"), repository.topKeywords(10));
    }

    @Test
    void cluesFromOlderPromptVersionsAreRequeuedButRetiredOnesAreNot() {
        long category = TestData.category(jdbc, "Film");
        long old = TestData.playableClue(jdbc, category, "easy", "Old?", "A", "Film");
        long retired = TestData.rawClue(jdbc, category, "easy", "Retired?", "B");
        jdbc.update("UPDATE clue SET enrich_version = 1 WHERE id = ?", old);
        jdbc.update("UPDATE clue SET retired_at = CURRENT_TIMESTAMP WHERE id = ?", retired);

        List<ClueToEnrich> pending = repository.findPending(10, MAX_ATTEMPTS);
        assertEquals(1, pending.size());
        assertEquals(old, pending.get(0).id());
    }

    @Test
    void cluesThatKeepFailingAreEventuallySkipped() {
        long category = TestData.category(jdbc, "Film");
        TestData.rawClue(jdbc, category, "easy", "Hard to convert?", "A");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            repository.recordAttempt(repository.findPending(10, MAX_ATTEMPTS));
        }
        assertEquals(0, repository.countPending(MAX_ATTEMPTS));
        repository.recordAttempt(List.of()); // no-op, must not fail
    }

    @Test
    void keywordsNeedFiveCluesBeforeTheyGetATitle() {
        long category = TestData.category(jdbc, "Film");
        for (int i = 0; i < 5; i++) {
            long id = TestData.rawClue(jdbc, category, "easy", "Star Wars Q" + i + "?", "A" + i);
            repository.save(new EnrichedClue(id, "c" + i, "What is A" + i + "?", List.of("Star Wars")));
        }
        long lonely = TestData.rawClue(jdbc, category, "easy", "Lonely?", "L");
        repository.save(new EnrichedClue(lonely, "c", "What is L?", List.of("Lonely Keyword")));

        List<KeywordToTitle> untitled = repository.findUntitledKeywords(5, 10);
        assertEquals(1, untitled.size());
        assertEquals("Star Wars", untitled.get(0).keyword());
        assertTrue(untitled.get(0).sampleResponses().size() <= 4);

        repository.saveTitle(untitled.get(0).id(), "May The Quiz Be With You");
        assertTrue(repository.findUntitledKeywords(5, 10).isEmpty());
    }
}
