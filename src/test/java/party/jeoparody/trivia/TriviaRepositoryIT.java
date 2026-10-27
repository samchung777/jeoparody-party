package party.jeoparody.trivia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

/**
 * Integration test (Failsafe, `*IT`): runs against real MySQL (docker-compose locally, the MySQL sidecar in CircleCI).
 * Checks de-duplication (question_hash), category upserts and the library's counts.
 */
class TriviaRepositoryIT extends IntegrationTest {

    @Autowired
    TriviaRepository repository;

    @Test
    void upsertCategoryIsIdempotentAndRenames() {
        long id = repository.upsertCategory("opentdb", 11, "Film");
        assertEquals(id, repository.upsertCategory("opentdb", 11, "Movies"));
        assertEquals("Movies", jdbc.queryForObject("SELECT name FROM category WHERE id = ?", String.class, id));
    }

    @Test
    void duplicateQuestionsAreIgnoredRegardlessOfCaseAndSpacing() {
        long category = repository.upsertCategory("opentdb", 11, "Film");
        assertTrue(repository.insertClueIfNew(category, "easy", "Who directed Jaws?", "Steven Spielberg"));
        assertFalse(repository.insertClueIfNew(category, "easy", "  who DIRECTED jaws?  ", "Steven Spielberg"));
        assertEquals(1, repository.countClues());
    }

    @Test
    void categoryCountsAndSampleCluesSkipRetiredClues() {
        long category = TestData.category(jdbc, "Film");
        long keep = TestData.playableClue(jdbc, category, "easy", "Q1?", "A1", "80s Movies");
        long retire = TestData.playableClue(jdbc, category, "easy", "Q2?", "A2", "80s Movies");
        jdbc.update("UPDATE clue SET retired_at = CURRENT_TIMESTAMP WHERE id = ?", retire);

        Category film = repository.listCategories().stream().filter(c -> c.id() == category).findFirst().orElseThrow();
        assertEquals(1, film.easy());
        List<Clue> clues = repository.findClues(category, 10);
        assertEquals(1, clues.size());
        assertEquals(keep, clues.get(0).id());
        assertEquals(List.of("80s Movies"), clues.get(0).keywords());
        assertEquals("What is A1?", clues.get(0).jeopardyResponse());
    }

    @Test
    void keywordStatsOnlyCountConvertedClues() {
        long category = TestData.category(jdbc, "Music");
        for (int i = 0; i < 5; i++) {
            TestData.playableClue(jdbc, category, i < 2 ? "easy" : "hard", "Music Q" + i + "?", "A" + i, "90s Music");
        }
        TestData.rawClue(jdbc, category, "easy", "Not converted yet?", "Nope");

        List<KeywordStats> stats = repository.keywordStats(5, 10);
        assertEquals(1, stats.size());
        KeywordStats s = stats.get(0);
        assertEquals("90s Music", s.name());
        assertEquals("Punny 90s Music", s.title());
        assertEquals(5, s.total());
        assertEquals(2, s.easy());
        assertEquals(3, s.hard());
        assertTrue(repository.keywordStats(6, 10).isEmpty());
    }
}
