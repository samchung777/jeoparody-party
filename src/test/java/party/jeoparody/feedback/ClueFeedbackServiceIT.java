package party.jeoparody.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import party.jeoparody.enrich.ClueToEnrich;
import party.jeoparody.enrich.EnrichmentRepository;
import party.jeoparody.game.GameRepository;
import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;
import party.jeoparody.trivia.TriviaRepository;

/**
 * Integration test (Failsafe, `*IT`): runs against real MySQL (docker-compose locally, the MySQL sidecar in CircleCI).
 * Checks host feedback end to end in the database: remove, reword, restore, remove/restore a category.
 */
class ClueFeedbackServiceIT extends IntegrationTest {

    @Autowired
    ClueFeedbackService feedback;
    @Autowired
    GameRepository games;
    @Autowired
    EnrichmentRepository enrichment;
    @Autowired
    TriviaRepository trivia;

    private long category;
    private long clue;
    private long keywordId;

    @BeforeEach
    void seed() {
        category = TestData.category(jdbc, "Film");
        clue = TestData.playableClue(jdbc, category, "hard", "Who directed Jaws?", "Steven Spielberg", "70s Movies");
        keywordId = jdbc.queryForObject("SELECT id FROM keyword WHERE name = '70s Movies'", Long.class);
    }

    @Test
    void removeRetiresTheClueAndKeepsItOutOfGames() {
        feedback.submit(clue, "remove", "wrong", "answer is outdated", "ABCD");

        assertNotNull(jdbc.queryForObject("SELECT retired_at FROM clue WHERE id = ?", Object.class, clue));
        assertTrue(games.cluesForKeyword(keywordId).isEmpty());
        Map<String, Object> row = jdbc.queryForMap("SELECT action, reason, note, game_code FROM clue_feedback WHERE clue_id = ?", clue);
        assertEquals("remove", row.get("action"));
        assertEquals("wrong", row.get("reason"));
        assertEquals("answer is outdated", row.get("note"));
        assertEquals("ABCD", row.get("game_code"));
    }

    @Test
    void aRemovedClueCannotComeBackThroughAReimport() {
        feedback.submit(clue, "remove", "obscure", null, null);
        assertFalse(trivia.insertClueIfNew(category, "hard", "Who directed Jaws?", "Steven Spielberg"));
    }

    @Test
    void rewordSendsTheClueBackToGeminiWithTheHostsNote() {
        feedback.submit(clue, "reword", "giveaway", "  mentions the shark  ", "ABCD");

        List<ClueToEnrich> pending = enrichment.findPending(10, 3);
        assertEquals(1, pending.size());
        assertEquals("The clue gave away the answer. Host note: mentions the shark", pending.get(0).hostFeedback());
        assertTrue(games.cluesForKeyword(keywordId).size() == 1, "still playable until rewritten");
    }

    @Test
    void unknownReasonsBecomeOtherAndBlankNotesAreDropped() {
        feedback.submit(clue, "reword", "made-up-reason", "   ", null);
        Map<String, Object> row = jdbc.queryForMap("SELECT reason, note FROM clue_feedback WHERE clue_id = ?", clue);
        assertEquals("other", row.get("reason"));
        assertNull(row.get("note"));
    }

    @Test
    void restoreBringsARemovedClueBack() {
        feedback.submit(clue, "remove", "other", null, null);
        feedback.restore(clue);
        assertEquals(1, games.cluesForKeyword(keywordId).size());
        assertEquals(2, TestData.count(jdbc, "SELECT COUNT(*) FROM clue_feedback WHERE clue_id = ?", clue));
    }

    @Test
    void recentListsFlagsNewestFirst() {
        long other = TestData.playableClue(jdbc, category, "easy", "Who is Indiana Jones?", "Harrison Ford", "80s Movies");
        feedback.submit(clue, "remove", "wrong", null, null);
        feedback.submit(other, "reword", "unclear", "too long", null);

        List<ClueFeedbackService.FlaggedClue> recent = feedback.recent(10);
        assertEquals(2, recent.size());
        assertEquals(other, recent.get(0).clueId());
        assertFalse(recent.get(0).retired());
        assertTrue(recent.get(1).retired());
    }

    @Test
    void unknownCluesAndActionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> feedback.submit(999_999, "remove", "wrong", null, null));
        assertThrows(IllegalArgumentException.class, () -> feedback.submit(clue, "delete", "wrong", null, null));
        assertThrows(IllegalArgumentException.class, () -> feedback.restore(999_999));
    }

    @Test
    void removingACategoryHidesItFromGamesAndSearchButKeepsItsClues() {
        for (int i = 0; i < 5; i++) {
            TestData.playableClue(jdbc, category, "easy", "Seventies Q" + i + "?", "A" + i, "70s Movies", "Jaws");
        }
        assertTrue(games.eligibleKeywords(5).stream().anyMatch(k -> k.id() == keywordId));

        feedback.removeKeyword(keywordId, "too broad", "ABCD");
        assertTrue(games.eligibleKeywords(5).stream().noneMatch(k -> k.id() == keywordId));
        assertTrue(games.eligibleKeywords(5).stream().anyMatch(k -> k.name().equals("Jaws")), "shared clues still play");
        assertEquals(0, TestData.count(jdbc, "SELECT COUNT(*) FROM clue WHERE retired_at IS NOT NULL"));
        List<ClueFeedbackService.RemovedCategory> removed = feedback.removedKeywords();
        assertEquals(1, removed.size());
        assertEquals("70s Movies", removed.get(0).name());
        assertEquals("game ABCD: too broad", removed.get(0).note(), "the note records which game it came from");

        feedback.restoreKeyword(keywordId);
        assertTrue(games.eligibleKeywords(5).stream().anyMatch(k -> k.id() == keywordId));
        assertTrue(feedback.removedKeywords().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> feedback.removeKeyword(999_999, null, null));
    }
}
