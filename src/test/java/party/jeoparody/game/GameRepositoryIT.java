package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

/**
 * Integration test (Failsafe, `*IT`): runs against real MySQL (docker-compose locally, the MySQL sidecar in CircleCI).
 * Checks which keywords are playable and that finished games are saved once.
 */
class GameRepositoryIT extends IntegrationTest {

    @Autowired
    GameRepository repository;
    @Autowired
    GameService games;

    @Test
    void onlyKeywordsWithEnoughPlayableCluesAreEligible() {
        long category = TestData.category(jdbc, "Film");
        for (int i = 0; i < 5; i++) {
            TestData.playableClue(jdbc, category, "easy", "Five Q" + i + "?", "A" + i, "Has Five");
        }
        for (int i = 0; i < 4; i++) {
            TestData.playableClue(jdbc, category, "easy", "Four Q" + i + "?", "B" + i, "Has Four");
        }
        TestData.rawClue(jdbc, category, "easy", "Unconverted?", "C");

        List<KeywordOption> eligible = repository.eligibleKeywords(5);
        assertEquals(1, eligible.size());
        assertEquals("Has Five", eligible.get(0).name());
        assertEquals("Punny Has Five", eligible.get(0).displayTitle());
        assertEquals(5, eligible.get(0).clueCount());

        long keywordId = eligible.get(0).id();
        List<CandidateClue> clues = repository.cluesForKeyword(keywordId);
        assertEquals(5, clues.size());
        assertTrue(clues.get(0).response().startsWith("What is "));

        jdbc.update("UPDATE clue SET retired_at = CURRENT_TIMESTAMP WHERE id = ?", clues.get(0).id());
        assertTrue(repository.eligibleKeywords(5).isEmpty(), "a retired clue drops the keyword below 5");
    }

    @Test
    void finishedGamesAreSavedOnceWithStandings() {
        Game game = games.create().game();
        Team a = game.join("Alpha", null);
        Team b = game.join("Bravo", null);
        game.adjustScore(a.id(), 400);
        game.adjustScore(b.id(), 1200);

        games.afterAction(game); // not over yet: nothing saved
        assertEquals(0, repository.countResults());

        game.finish();
        games.afterAction(game);
        games.afterAction(game); // saved only once
        assertEquals(1, repository.countResults());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT place, team_name, score FROM game_result_team ORDER BY place");
        assertEquals("Bravo", rows.get(0).get("team_name"));
        assertEquals(1200, ((Number) rows.get(0).get("score")).intValue());
        assertEquals("Alpha", rows.get(1).get("team_name"));
        assertEquals(game.code(), jdbc.queryForObject("SELECT code FROM game_result", String.class));
    }
}
