package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rules of the game, end to end, without Spring or a database. */
@SuppressWarnings("unchecked")
class GameTest {

    private FakeClueSource source;
    private Game game;
    private Team red;
    private Team blue;
    private Team green;

    @BeforeEach
    void setUp() {
        source = new FakeClueSource(30);
        AtomicInteger ids = new AtomicInteger();
        game = new Game("TEST", "host-key", new BoardBuilder(new Random(42)), () -> "t" + ids.incrementAndGet(),
                Clock.systemUTC());
        red = game.join("  Red   Team ", "data:image/png;base64,AAAA");
        blue = game.join("", "not-a-png");
        green = game.join("Green", null);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> view(Game.Viewer viewer) {
        return game.view(viewer, null);
    }

    private List<Long> poolIds() {
        return ((List<Map<String, Object>>) view(Game.Viewer.HOST).get("pool")).stream()
                .map(m -> (Long) m.get("id")).toList();
    }

    private List<Map<String, Object>> hostCategories() {
        return (List<Map<String, Object>>) ((Map<String, Object>) view(Game.Viewer.HOST).get("board")).get("categories");
    }

    private Map<String, Object> hostCell(int column, int row) {
        return ((List<Map<String, Object>>) hostCategories().get(column).get("cells")).get(row);
    }

    /** Votes, closes both votes and lands on the Jeopardy board. */
    private void playToBoard() {
        game.startVoting(source);
        List<Long> pool = poolIds();
        game.vote(red.id(), pool.subList(0, 12));
        game.vote(blue.id(), pool.subList(0, 6));
        game.closeVoting(source);
        List<Map<String, Object>> finals = (List<Map<String, Object>>) view(Game.Viewer.HOST).get("finalOptions");
        game.voteFinal(red.id(), (Long) finals.get(0).get("id"));
        game.closeFinalVoting(source);
    }

    private int[] findDailyDouble() {
        for (int c = 0; c < Game.COLUMNS; c++) {
            for (int r = 0; r < Game.ROWS; r++) {
                if ((Boolean) hostCell(c, r).get("dailyDouble")) {
                    return new int[] {c, r};
                }
            }
        }
        throw new AssertionError("no Daily Double on the board");
    }

    private int[] findNormalCell() {
        for (int c = 0; c < Game.COLUMNS; c++) {
            for (int r = 0; r < Game.ROWS; r++) {
                Map<String, Object> cell = hostCell(c, r);
                if (!(Boolean) cell.get("dailyDouble") && !(Boolean) cell.get("used")) {
                    return new int[] {c, r};
                }
            }
        }
        throw new AssertionError("no free cell");
    }

    /** Plays every remaining clue of the current round with "time's up". */
    private void exhaustRound() {
        for (int c = 0; c < Game.COLUMNS; c++) {
            for (int r = 0; r < Game.ROWS; r++) {
                if (game.phase() != Phase.BOARD) {
                    return;
                }
                if ((Boolean) hostCell(c, r).get("used")) {
                    continue;
                }
                game.selectCell(c, r);
                if (game.phase() == Phase.DAILY_DOUBLE_WAGER) {
                    String control = (String) view(Game.Viewer.HOST).get("controlTeamId");
                    game.wager(control, 5);
                }
                game.reveal();
                game.returnToBoard();
            }
        }
    }

    // ------------------------------------------------------------------ lobby

    @Test
    void joinCleansNamesAndRejectsNonPngDrawings() {
        assertEquals("Red Team", red.name());
        assertEquals("Team 2", blue.name());
        assertNull(blue.drawing());
        assertNotNull(red.drawing());
    }

    @Test
    void rejoinRequiresTheTeamsToken() {
        assertEquals(red, game.rejoin(red.id(), red.token()));
        assertThrows(GameException.class, () -> game.rejoin(red.id(), "wrong-token"));
    }

    @Test
    void cannotStartVotingWithoutTeams() {
        Game empty = new Game("EMPT", "k", new BoardBuilder(new Random(1)), () -> "x", Clock.systemUTC());
        assertThrows(GameException.class, () -> empty.startVoting(source));
    }

    @Test
    void cannotStartWithTooFewKeywords() {
        FakeClueSource tiny = new FakeClueSource(Game.BOARD_CATEGORIES);
        GameException e = assertThrows(GameException.class, () -> game.startVoting(tiny));
        assertTrue(e.getMessage().contains("Import more trivia"));
    }

    @Test
    void hostKeyIsChecked() {
        assertTrue(game.isHostKey("host-key"));
        assertFalse(game.isHostKey("nope"));
        assertFalse(game.isHostKey(null));
    }

    // ------------------------------------------------------------------ voting

    @Test
    void votingOffersAPoolOf26AndValidatesBallots() {
        game.startVoting(source);
        List<Long> pool = poolIds();
        assertEquals(Game.POOL_SIZE, pool.size());
        assertThrows(GameException.class, () -> game.vote(red.id(), List.of(999L)));
        assertThrows(GameException.class, () -> game.vote(red.id(), pool.subList(0, 13)));
        game.vote(red.id(), pool.subList(0, 12));
        assertTrue((Boolean) ((List<Map<String, Object>>) view(Game.Viewer.HOST).get("teams")).get(0).get("voted"));
    }

    @Test
    void playersDoNotSeeVoteCounts() {
        game.startVoting(source);
        game.vote(red.id(), poolIds().subList(0, 3));
        Map<String, Object> first = ((List<Map<String, Object>>) game.view(Game.Viewer.PLAYER, red.id())
                .get("pool")).get(0);
        assertFalse(first.containsKey("votes"));
        Map<String, Object> hostFirst = ((List<Map<String, Object>>) view(Game.Viewer.HOST).get("pool")).get(0);
        assertTrue(hostFirst.containsKey("votes"));
    }

    @Test
    void closingTheVoteBuildsTwoFullBoardsAndOffersTheRestForFinal() {
        playToBoard();
        assertEquals(Phase.BOARD, game.phase());
        assertEquals("JEOPARDY", view(Game.Viewer.HOST).get("round"));
        assertEquals(Game.COLUMNS, hostCategories().size());
        List<Integer> values = ((List<Map<String, Object>>) hostCategories().get(0).get("cells")).stream()
                .map(m -> (Integer) m.get("value")).toList();
        assertEquals(List.of(200, 400, 600, 800, 1000), values);
    }

    @Test
    void topVotedKeywordsAlternateBetweenRounds() {
        game.startVoting(source);
        List<Long> pool = poolIds();
        game.vote(red.id(), pool.subList(0, 6));
        game.vote(blue.id(), pool.subList(0, 6));
        game.closeVoting(source);
        Set<String> jeopardyKeywords = new HashSet<>();
        // the J board is only visible once play starts
        List<Map<String, Object>> finals = (List<Map<String, Object>>) view(Game.Viewer.HOST).get("finalOptions");
        assertEquals(Game.POOL_SIZE - Game.BOARD_CATEGORIES, finals.size());
        game.closeFinalVoting(source);
        hostCategories().forEach(c -> jeopardyKeywords.add((String) c.get("keyword")));
        long top6InJeopardy = pool.subList(0, 6).stream().filter(id -> jeopardyKeywords.contains("Keyword " + id)).count();
        assertEquals(3, top6InJeopardy);
    }

    // ------------------------------------------------------------------ clues and buzzers

    @Test
    void boardAndPlayersNeverSeeTheResponseBeforeReveal() {
        playToBoard();
        int[] cell = findNormalCell();
        game.selectCell(cell[0], cell[1]);
        assertFalse(((Map<String, Object>) view(Game.Viewer.BOARD).get("clue")).containsKey("response"));
        assertFalse(((Map<String, Object>) game.view(Game.Viewer.PLAYER, red.id()).get("clue")).containsKey("response"));
        assertTrue(((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).containsKey("response"));
        game.reveal();
        assertTrue(((Map<String, Object>) view(Game.Viewer.BOARD).get("clue")).containsKey("response"));
    }

    @Test
    void onlyTheHostSeesDailyDoubleLocations() {
        playToBoard();
        Map<String, Object> boardCell = ((List<Map<String, Object>>) ((List<Map<String, Object>>)
                ((Map<String, Object>) view(Game.Viewer.BOARD).get("board")).get("categories")).get(0).get("cells")).get(0);
        assertFalse(boardCell.containsKey("dailyDouble"));
        assertNotNull(findDailyDouble());
    }

    @Test
    void buzzingOnlyWorksWhileBuzzersAreOpenAndFirstBuzzWins() {
        playToBoard();
        int[] cell = findNormalCell();
        game.selectCell(cell[0], cell[1]);
        assertFalse(game.buzz(red.id()), "early buzz ignored");
        game.openBuzzers();
        assertTrue(game.buzz(blue.id()));
        assertFalse(game.buzz(red.id()), "second buzz ignored");
        assertEquals(blue.id(), ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("answeringTeamId"));
    }

    @Test
    void wrongAnswerDeductsLocksOutAndReopensBuzzers() {
        playToBoard();
        int[] cell = findNormalCell();
        int value = (Integer) hostCell(cell[0], cell[1]).get("value");
        game.selectCell(cell[0], cell[1]);
        game.openBuzzers();
        game.buzz(blue.id());
        game.judge(false);
        assertEquals(-value, blue.score());
        assertFalse(game.buzz(blue.id()), "locked out");
        assertTrue(game.buzz(red.id()), "rebound");
        game.judge(true);
        assertEquals(value, red.score());
        assertEquals(red.id(), view(Game.Viewer.HOST).get("controlTeamId"), "correct team takes control");
    }

    @Test
    void clueIsRevealedWhenEveryTeamHasMissed() {
        playToBoard();
        int[] cell = findNormalCell();
        game.selectCell(cell[0], cell[1]);
        game.openBuzzers();
        for (Team t : List.of(red, blue, green)) {
            game.buzz(t.id());
            game.judge(false);
        }
        assertTrue((Boolean) ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("revealed"));
    }

    @Test
    void usedCluesCannotBeReplayed() {
        playToBoard();
        int[] cell = findNormalCell();
        game.selectCell(cell[0], cell[1]);
        assertThrows(GameException.class, () -> game.selectCell(0, 0), "clue already open");
        game.reveal();
        game.returnToBoard();
        assertThrows(GameException.class, () -> game.selectCell(cell[0], cell[1]));
    }

    // ------------------------------------------------------------------ Daily Double

    @Test
    void dailyDoubleWagerRulesAndScoring() {
        playToBoard();
        game.setControl(green.id());
        int[] dd = findDailyDouble();
        game.selectCell(dd[0], dd[1]);
        assertEquals(Phase.DAILY_DOUBLE_WAGER, game.phase());
        assertFalse(((Map<String, Object>) view(Game.Viewer.BOARD).get("clue")).containsKey("text"),
                "clue hidden until the wager is in");
        assertThrows(GameException.class, () -> game.wager(red.id(), 100), "only the team in control");
        assertThrows(GameException.class, () -> game.wager(green.id(), 4), "minimum $5");
        assertThrows(GameException.class, () -> game.wager(green.id(), 1001), "max is the round's top value");
        game.wager(green.id(), 1000);
        assertThrows(GameException.class, () -> game.openBuzzers(), "no buzzers on a Daily Double");
        game.judge(false);
        assertEquals(-1000, green.score());
        assertTrue((Boolean) ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("revealed"));
    }

    @Test
    void dailyDoubleMaxIsTheTeamsScoreWhenHigher() {
        playToBoard();
        game.adjustScore(red.id(), 5000);
        assertEquals(5000, game.dailyDoubleMaxWager(red.id()));
        assertEquals(1000, game.dailyDoubleMaxWager(blue.id()));
    }

    // ------------------------------------------------------------------ rounds

    @Test
    void doubleJeopardyHasDoubledValuesTwoDailyDoublesAndTheTrailingTeamStarts() {
        playToBoard();
        game.adjustScore(red.id(), 400);
        game.adjustScore(blue.id(), -200);
        exhaustRound();
        assertEquals("DOUBLE_JEOPARDY", view(Game.Viewer.HOST).get("round"));
        assertEquals(blue.id(), view(Game.Viewer.HOST).get("controlTeamId"));
        assertEquals(2000, hostCell(0, 4).get("value"));
        int dailyDoubles = 0;
        Set<Integer> columns = new HashSet<>();
        for (int c = 0; c < Game.COLUMNS; c++) {
            for (int r = 0; r < Game.ROWS; r++) {
                if ((Boolean) hostCell(c, r).get("dailyDouble")) {
                    dailyDoubles++;
                    columns.add(c);
                }
            }
        }
        assertEquals(2, dailyDoubles);
        assertEquals(2, columns.size(), "Daily Doubles are in different categories");
    }

    @Test
    void noClueAppearsTwiceInAGame() {
        playToBoard();
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 2; round++) {
            for (int c = 0; c < Game.COLUMNS; c++) {
                for (int r = 0; r < Game.ROWS; r++) {
                    game.selectCell(c, r);
                    if (game.phase() == Phase.DAILY_DOUBLE_WAGER) {
                        game.wager((String) view(Game.Viewer.HOST).get("controlTeamId"), 5);
                    }
                    String text = (String) ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("text");
                    assertTrue(seen.add(text), "duplicate clue: " + text);
                    game.reveal();
                    game.returnToBoard();
                }
            }
        }
        assertEquals(60, seen.size());
    }

    // ------------------------------------------------------------------ Final Jeopardy

    private void playToFinal() {
        playToBoard();
        game.adjustScore(red.id(), 200);
        game.adjustScore(blue.id(), 300);
        game.adjustScore(green.id(), 1000);
        game.endRound();
        game.endRound();
        assertEquals(Phase.FINAL_WAGER, game.phase());
    }

    @Test
    void onlyTeamsWithPositiveScoresPlayFinal() {
        playToBoard();
        game.adjustScore(red.id(), 500);
        game.adjustScore(blue.id(), -100);
        game.endRound();
        game.endRound();
        assertThrows(GameException.class, () -> game.wager(blue.id(), 0));
        assertThrows(GameException.class, () -> game.wager(green.id(), 0), "zero score sits out");
        game.wager(red.id(), 500);
    }

    @Test
    void gameEndsWithoutFinalWhenNobodyIsPositive() {
        playToBoard();
        game.endRound();
        game.endRound();
        assertEquals(Phase.GAME_OVER, game.phase());
    }

    @Test
    void finalJeopardyFlow() {
        playToFinal();
        Map<String, Object> boardFinal = (Map<String, Object>) view(Game.Viewer.BOARD).get("final");
        assertTrue(boardFinal.containsKey("category"));
        assertFalse(boardFinal.containsKey("text"));
        assertFalse(boardFinal.containsKey("response"));

        assertThrows(GameException.class, () -> game.wager(red.id(), 201), "capped at score");
        game.wager(red.id(), 200);
        game.wager(green.id(), 999);
        // blue never wagers: counts as $0
        game.revealFinalClue();
        game.submitFinalAnswer(red.id(), "data:image/png;base64,BBBB", "What is 5?");
        game.submitFinalAnswer(green.id(), null, "Who knows?");
        game.lockFinalAnswers();

        Map<String, Object> hostFinal = (Map<String, Object>) view(Game.Viewer.HOST).get("final");
        assertEquals(List.of(red.id(), blue.id(), green.id()), hostFinal.get("revealOrder"), "lowest score first");

        game.judgeFinal(red.id(), true);
        assertThrows(GameException.class, () -> game.judgeFinal(red.id(), true), "judged once");
        game.judgeFinal(blue.id(), true);
        game.judgeFinal(green.id(), false);
        assertEquals(400, red.score());
        assertEquals(300, blue.score());
        assertEquals(1, green.score());

        game.finish();
        List<Map<String, Object>> standings = (List<Map<String, Object>>) view(Game.Viewer.BOARD).get("standings");
        assertEquals("Red Team", standings.get(0).get("name"));
    }

    @Test
    void playersCannotSeeOtherTeamsFinalWagers() {
        playToFinal();
        game.wager(red.id(), 100);
        game.wager(green.id(), 100);
        Map<String, Object> mine = (Map<String, Object>) game.view(Game.Viewer.PLAYER, red.id()).get("final");
        long othersVisible = ((List<Map<String, Object>>) mine.get("entries")).stream()
                .filter(e -> !red.id().equals(e.get("teamId")) && e.containsKey("wager")).count();
        assertEquals(0, othersVisible);
    }

    // ------------------------------------------------------------------ feedback

    @Test
    void hostFeedbackIsValidatedAndShownInTheReview() {
        playToBoard();
        int[] cell = findNormalCell();
        game.selectCell(cell[0], cell[1]);
        long clueId = (Long) ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("clueId");
        assertFalse(((Map<String, Object>) view(Game.Viewer.BOARD).get("clue")).containsKey("clueId"));

        assertThrows(GameException.class, () -> game.recordFeedback(999_999L, "remove"), "not in this game");
        assertThrows(GameException.class, () -> game.recordFeedback(clueId, "explode"), "unknown action");
        game.recordFeedback(clueId, "reword");
        assertEquals("reword", ((Map<String, Object>) view(Game.Viewer.HOST).get("clue")).get("feedback"));

        game.reveal();
        game.returnToBoard();
        game.finish();
        List<Map<String, Object>> played = (List<Map<String, Object>>) view(Game.Viewer.HOST).get("played");
        assertEquals(1, played.size());
        assertEquals("reword", played.get(0).get("feedback"));
        assertFalse(view(Game.Viewer.BOARD).containsKey("played"), "review list is host-only");
    }

    // ------------------------------------------------------------------ teams

    @Test
    void removingTheControllingTeamPassesControl() {
        playToBoard();
        game.setControl(red.id());
        game.removeTeam(red.id());
        assertEquals(2, game.teams().size());
        assertNotNull(view(Game.Viewer.HOST).get("controlTeamId"));
        assertThrows(GameException.class, () -> game.adjustScore(red.id(), 100));
    }
}
