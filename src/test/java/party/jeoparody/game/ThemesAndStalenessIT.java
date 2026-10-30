package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import party.jeoparody.enrich.EnrichmentProperties;
import party.jeoparody.genre.GenreClassifier;
import party.jeoparody.genre.GenreRepository;
import party.jeoparody.genre.GenreService;
import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

/**
 * Integration test (Failsafe, `*IT`): staleness and genres in real MySQL, and the game server's end-of-game hook
 * (staleness saved per clue, stale categories and suggestions queued for research).
 */
class ThemesAndStalenessIT extends IntegrationTest {

    @Autowired
    GameRepository repository;
    @Autowired
    GameService games;
    @Autowired
    GenreRepository genreRepository;

    private long keywordId(String name) {
        return jdbc.queryForObject("SELECT id FROM keyword WHERE name = ?", Long.class, name);
    }

    @Test
    void shownCluesGetStalerAndBoardsSeeIt() {
        TestData.seedPlayableTrivia(jdbc, 1);
        List<CandidateClue> clues = repository.cluesForKeyword(keywordId("Theme 1"));
        assertTrue(clues.stream().allMatch(c -> c.staleness() == 0), "new clues start fresh");
        long first = clues.get(0).id();
        repository.markShown(List.of(first));
        repository.markShown(List.of(first, clues.get(1).id()));
        repository.markShown(List.of()); // no-op
        Map<Long, Integer> staleness = new java.util.HashMap<>();
        repository.cluesForKeyword(keywordId("Theme 1")).forEach(c -> staleness.put(c.id(), c.staleness()));
        assertEquals(2, staleness.get(first));
        assertEquals(1, staleness.get(clues.get(1).id()));
    }

    @Test
    void genresComeFromRulesThenGeminiAndReachTheGame() throws Exception {
        long film = TestData.category(jdbc, "Film");
        long wiki = TestData.category(jdbc, "Naruto (manga)");
        for (int i = 0; i < 5; i++) {
            TestData.playableClue(jdbc, film, "easy", "Time travel Q" + i + "?", "T" + i, "Time Travel");
            TestData.playableClue(jdbc, wiki, "easy", "Naruto Q" + i + "?", "N" + i, "Naruto");
            TestData.playableClue(jdbc, wiki, "easy", "Shounen Q" + i + "?", "S" + i, "Shounen Anime");
        }
        GenreClassifier gemini = mock(GenreClassifier.class);
        when(gemini.classify(anyList())).thenAnswer(inv -> Map.of(keywordId("Naruto"), Set.of("Anime & Manga")));
        GenreService service = new GenreService(genreRepository, gemini,
                new EnrichmentProperties(true, "p", "global", "m", 20, 3));

        assertEquals(2, service.assignByRules(), "Film majority and the word 'Anime' decide two of three");
        service.classifyPending();
        Map<String, Set<String>> byName = new java.util.HashMap<>();
        repository.eligibleKeywords(5).forEach(k -> byName.put(k.name(), k.genres()));
        assertEquals(Set.of("Movies"), byName.get("Time Travel"));
        assertEquals(Set.of("Anime & Manga"), byName.get("Shounen Anime"));
        assertEquals(Set.of("Anime & Manga"), byName.get("Naruto"), "classified by Gemini");
        assertEquals(0, service.classifyPending(), "checked keywords aren't asked about again");
        assertEquals(Map.of("Anime & Manga", 2, "Movies", 1), genreRepository.counts());
    }

    @Test
    void theGameServerSavesStalenessAndQueuesResearchWhenTheGameEnds() {
        TestData.seedPlayableTrivia(jdbc, 30);
        jdbc.update("UPDATE clue cl JOIN clue_keyword ck ON ck.clue_id = cl.id JOIN keyword k ON k.id = ck.keyword_id"
                + " SET cl.staleness = 2 WHERE k.name = 'Theme 7'"); // every Theme 7 clue already shown twice
        jdbc.update("INSERT INTO topic_suggestion (topic, game_code, suggested_by) VALUES ('Gravity Falls', 'ABCD', 'Red')");

        Game game = games.create().game();
        Team red = game.join("Red", null);
        game.startVoting(games.clueSource());
        List<Long> pool = game.view(Game.Viewer.HOST, null).get("pool") instanceof List<?> l
                ? l.stream().map(m -> (Long) ((Map<?, ?>) m).get("id")).toList() : List.of();
        game.vote(red.id(), pool.subList(0, 12));
        game.closeVoting(games.clueSource());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finals = (List<Map<String, Object>>) game.view(Game.Viewer.HOST, null).get("finalOptions");
        game.voteFinal(red.id(), (Long) finals.get(0).get("id"));
        game.closeFinalVoting(games.clueSource());
        game.selectCell(0, 0);
        if (game.phase() == Phase.DAILY_DOUBLE_WAGER) {
            game.wager(red.id(), 5);
        }
        @SuppressWarnings("unchecked")
        long shown = (Long) ((Map<String, Object>) game.view(Game.Viewer.HOST, null).get("clue")).get("clueId");
        game.reveal();
        game.returnToBoard();
        games.afterAction(game);
        assertEquals(1, jdbc.queryForObject("SELECT staleness FROM clue WHERE id = ?", Integer.class, shown));

        game.finish();
        games.afterAction(game);
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT kind, topic, status FROM research_task ORDER BY id");
        assertEquals(2, tasks.size(), tasks.toString());
        assertEquals("stale_category", tasks.get(0).get("kind"));
        assertEquals("Theme 7", tasks.get(0).get("topic"));
        assertEquals("suggestion", tasks.get(1).get("kind"));
        assertEquals("Gravity Falls", tasks.get(1).get("topic"));
        assertEquals("queued", jdbc.queryForObject("SELECT status FROM topic_suggestion", String.class));

        games.afterAction(game); // saving again (another action at Game Over) plans nothing new
        assertEquals(2, TestData.count(jdbc, "SELECT COUNT(*) FROM research_task"));
    }
}
