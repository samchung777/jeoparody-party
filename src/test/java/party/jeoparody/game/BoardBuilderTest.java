package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Uses a seeded Random, so board building, vote ranking and Daily Double placement are repeatable.
 */
class BoardBuilderTest {

    private final BoardBuilder builder = new BoardBuilder(new Random(7));
    private final KeywordOption keyword = new KeywordOption(1, "80s Movies", "Totally Tubular Cinema", 7);

    private static List<CandidateClue> clues(String... difficulties) {
        List<CandidateClue> list = new ArrayList<>();
        for (int i = 0; i < difficulties.length; i++) {
            list.add(new CandidateClue(i + 1, difficulties[i], "clue " + (i + 1), "What is " + (i + 1) + "?"));
        }
        return list;
    }

    @Test
    void rankOrdersByVotesAndIgnoresDuplicateVotesOnOneBallot() {
        List<KeywordOption> pool = List.of(
                new KeywordOption(1, "a", null, 5), new KeywordOption(2, "b", null, 5), new KeywordOption(3, "c", null, 5));
        List<Long> ranked = builder.rank(List.of(List.of(2L, 2L, 2L), List.of(3L, 2L), List.of(2L)), pool);
        assertEquals(List.of(2L, 3L, 1L), ranked);
    }

    @Test
    void rankIgnoresVotesForKeywordsOutsideThePool() {
        List<KeywordOption> pool = List.of(new KeywordOption(1, "a", null, 5));
        assertEquals(List.of(1L), builder.rank(List.of(List.of(99L)), pool));
    }

    @Test
    void columnsGoFromEasyAtTheTopToHardAtTheBottom() {
        Optional<BoardCategory> category = builder.buildCategory(keyword,
                clues("hard", "easy", "medium", "hard", "easy"), new HashSet<>(), Round.JEOPARDY, 0);
        assertTrue(category.isPresent());
        List<String> difficulties = category.get().cells().stream().map(c -> c.clue().difficulty()).toList();
        assertEquals(List.of("easy", "easy", "medium", "hard", "hard"), difficulties);
        assertEquals(List.of(200, 400, 600, 800, 1000), category.get().cells().stream().map(BoardCell::value).toList());
    }

    @Test
    void missingDifficultiesFallBackToTheNearestOne() {
        Optional<BoardCategory> category = builder.buildCategory(keyword,
                clues("medium", "medium", "medium", "medium", "medium"), new HashSet<>(), Round.DOUBLE_JEOPARDY, 2);
        assertTrue(category.isPresent());
        assertEquals(2000, category.get().cells().get(4).value());
        assertEquals(2, category.get().cells().get(0).column());
    }

    @Test
    void alreadyUsedCluesAreSkippedAndTooFewMeansNoCategory() {
        Set<Long> used = new HashSet<>(List.of(1L, 2L));
        assertFalse(builder.buildCategory(keyword, clues("easy", "easy", "medium", "hard", "hard", "hard"), used,
                Round.JEOPARDY, 0).isPresent());
        Set<Long> fresh = new HashSet<>();
        builder.buildCategory(keyword, clues("easy", "easy", "medium", "hard", "hard"), fresh, Round.JEOPARDY, 0);
        assertEquals(5, fresh.size(), "chosen clues are marked used");
    }

    @Test
    void dailyDoublesGoInDifferentColumns() {
        List<BoardCategory> categories = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (int col = 0; col < Game.COLUMNS; col++) {
            List<CandidateClue> list = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                list.add(new CandidateClue(col * 10L + i, "medium", "c", "r"));
            }
            categories.add(builder.buildCategory(keyword, list, used, Round.DOUBLE_JEOPARDY, col).orElseThrow());
        }
        for (int seed = 0; seed < 50; seed++) {
            Board board = new Board(Round.DOUBLE_JEOPARDY, rebuild(categories));
            new BoardBuilder(new Random(seed)).placeDailyDoubles(board);
            Set<Integer> columns = new HashSet<>();
            int count = 0;
            for (BoardCategory c : board.categories()) {
                for (BoardCell cell : c.cells()) {
                    if (cell.dailyDouble()) {
                        count++;
                        columns.add(cell.column());
                    }
                }
            }
            assertEquals(2, count);
            assertEquals(2, columns.size());
        }
    }

    private static List<BoardCategory> rebuild(List<BoardCategory> categories) {
        List<BoardCategory> copy = new ArrayList<>();
        for (BoardCategory c : categories) {
            List<BoardCell> cells = new ArrayList<>();
            for (BoardCell cell : c.cells()) {
                cells.add(new BoardCell(cell.column(), cell.row(), cell.value(), cell.clue()));
            }
            copy.add(new BoardCategory(c.keyword(), cells));
        }
        return copy;
    }

    @Test
    void finalCluePrefersHardThenMediumAndSkipsUsed() {
        List<CandidateClue> list = clues("easy", "medium", "hard");
        assertEquals("hard", builder.pickFinalClue(list, new HashSet<>()).orElseThrow().difficulty());
        assertEquals("medium", builder.pickFinalClue(list, new HashSet<>(List.of(3L))).orElseThrow().difficulty());
        assertTrue(builder.pickFinalClue(list, new HashSet<>(List.of(1L, 2L, 3L))).isEmpty());
    }

    @Test
    void poolIsARandomSampleOfTheRequestedSize() {
        List<KeywordOption> eligible = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            eligible.add(new KeywordOption(i, "k" + i, null, 5));
        }
        List<KeywordOption> pool = builder.pickPool(eligible, 26);
        assertEquals(26, pool.size());
        assertEquals(26, new HashSet<>(pool).size());
        assertEquals(3, builder.pickPool(eligible.subList(0, 3), 26).size());
    }

    @Test
    void theLeastShownCluesAreUsedFirst() {
        List<CandidateClue> candidates = List.of(
                new CandidateClue(1, "easy", "shown 3 times", "r", 3),
                new CandidateClue(2, "easy", "never shown", "r", 0),
                new CandidateClue(3, "easy", "shown once", "r", 1),
                new CandidateClue(4, "medium", "m", "r", 2),
                new CandidateClue(5, "hard", "h1", "r", 5),
                new CandidateClue(6, "hard", "h2", "r", 0),
                new CandidateClue(7, "hard", "h3", "r", 1));
        for (int seed = 0; seed < 20; seed++) {
            BoardCategory c = new BoardBuilder(new Random(seed))
                    .buildCategory(keyword, candidates, new HashSet<>(), Round.JEOPARDY, 0).orElseThrow();
            List<Long> ids = c.cells().stream().map(cell -> cell.clue().id()).toList();
            assertEquals(List.of(2L, 3L, 4L, 6L, 7L), ids, "the stalest easy (1) and hard (5) clues sit out");
        }
    }

    @Test
    void finalCluePrefersTheLeastShownHardClue() {
        List<CandidateClue> list = List.of(new CandidateClue(1, "hard", "a", "r", 2),
                new CandidateClue(2, "hard", "b", "r", 0), new CandidateClue(3, "easy", "c", "r", 0));
        for (int seed = 0; seed < 20; seed++) {
            assertEquals(2L, new BoardBuilder(new Random(seed)).pickFinalClue(list, new HashSet<>()).orElseThrow().id());
        }
    }
}
