package party.jeoparody.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Pure logic for turning votes and keywords into boards: no I/O, deterministic given the {@link Random}.
 *
 * Where it fits: Game calls it to draw the voting pool, rank votes, fill each category column, hide Daily
 * Doubles and pick the Final clue. Why it matters: all randomness lives here behind one Random, so tests pass a
 * seeded Random and get the same board every time (BoardBuilderTest).
 */
public final class BoardBuilder {

    /** Which difficulty to try first for each row (top row easiest, bottom rows hardest). */
    private static final List<List<String>> ROW_PREFERENCES = List.of(
            List.of("easy", "medium", "hard"),
            List.of("easy", "medium", "hard"),
            List.of("medium", "easy", "hard"),
            List.of("hard", "medium", "easy"),
            List.of("hard", "medium", "easy"));

    /** Daily Doubles mostly hide in the lower rows, as on the show. */
    private static final int[] DAILY_DOUBLE_ROW_WEIGHTS = {1, 3, 6, 9, 7};

    private final Random random;

    /** GameService passes a Random seeded from SecureRandom; tests pass a fixed seed. */
    public BoardBuilder(Random random) {
        this.random = random;
    }

    /** A random sample of {@code size} keywords (or all of them if fewer). */
    public List<KeywordOption> pickPool(List<KeywordOption> eligible, int size) {
        List<KeywordOption> shuffled = new ArrayList<>(eligible);
        java.util.Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, Math.min(size, shuffled.size())));
    }

    /**
     * Orders keyword ids by vote count, most first. Ties (including keywords nobody voted for)
     * are broken randomly so the board isn't biased toward the pool's order.
     */
    public List<Long> rank(Collection<? extends Collection<Long>> ballots, List<KeywordOption> pool) {
        Map<Long, Integer> counts = new HashMap<>();
        pool.forEach(k -> counts.put(k.id(), 0));
        for (Collection<Long> ballot : ballots) {
            for (Long id : new LinkedHashSet<>(ballot)) {
                counts.computeIfPresent(id, (k, n) -> n + 1);
            }
        }
        List<Long> ids = new ArrayList<>(counts.keySet());
        ids.sort(Comparator.naturalOrder());           // stable starting point
        java.util.Collections.shuffle(ids, random);     // random tie-break
        ids.sort(Comparator.comparing((Long id) -> counts.get(id)).reversed());
        return ids;
    }

    /**
     * Builds a five-clue column for the keyword, skipping clues already used in this game.
     * Returns empty if fewer than five unused clues are available.
     */
    public Optional<BoardCategory> buildCategory(KeywordOption keyword, List<CandidateClue> candidates,
            Set<Long> usedClueIds, Round round, int column) {
        Map<String, List<CandidateClue>> byDifficulty = new HashMap<>();
        for (CandidateClue c : candidates) {
            if (!usedClueIds.contains(c.id())) {
                byDifficulty.computeIfAbsent(c.difficulty(), d -> new ArrayList<>()).add(c);
            }
        }
        // Random order first, then least-shown clues last (they are taken from the end of each list), so the
        // freshest clues win and ties are broken randomly.
        byDifficulty.values().forEach(list -> {
            java.util.Collections.shuffle(list, random);
            list.sort(Comparator.comparingInt(CandidateClue::staleness).reversed());
        });
        if (byDifficulty.values().stream().mapToInt(List::size).sum() < Game.ROWS) {
            return Optional.empty();
        }

        List<BoardCell> cells = new ArrayList<>();
        for (int row = 0; row < Game.ROWS; row++) {
            CandidateClue chosen = null;
            for (String difficulty : ROW_PREFERENCES.get(row)) {
                List<CandidateClue> list = byDifficulty.getOrDefault(difficulty, List.of());
                if (!list.isEmpty()) {
                    chosen = list.remove(list.size() - 1);
                    break;
                }
            }
            cells.add(new BoardCell(column, row, round.value(row), chosen));
        }
        cells.forEach(c -> usedClueIds.add(c.clue().id()));
        return Optional.of(new BoardCategory(keyword, List.copyOf(cells)));
    }

    /** Hides the round's Daily Doubles in different columns, weighted toward the lower rows. */
    public void placeDailyDoubles(Board board) {
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < board.categories().size(); i++) {
            columns.add(i);
        }
        java.util.Collections.shuffle(columns, random);
        int count = Math.min(board.round().dailyDoubles(), columns.size());
        for (int i = 0; i < count; i++) {
            board.cell(columns.get(i), weightedRow()).markDailyDouble();
        }
    }

    /** Final Jeopardy should be hard; fall back to medium, then easy. Among those, the least-shown clues. */
    public Optional<CandidateClue> pickFinalClue(List<CandidateClue> candidates, Set<Long> usedClueIds) {
        for (String difficulty : List.of("hard", "medium", "easy")) {
            List<CandidateClue> unused = candidates.stream()
                    .filter(c -> difficulty.equals(c.difficulty()) && !usedClueIds.contains(c.id()))
                    .toList();
            int freshest = unused.stream().mapToInt(CandidateClue::staleness).min().orElse(0);
            List<CandidateClue> options = unused.stream().filter(c -> c.staleness() == freshest).toList();
            if (!options.isEmpty()) {
                return Optional.of(options.get(random.nextInt(options.size())));
            }
        }
        return Optional.empty();
    }

    /**
     * Picks a row using DAILY_DOUBLE_ROW_WEIGHTS: rows 3 and 4 (the $600-$1000 / $1200-$2000 rows) are most likely.
     */
    private int weightedRow() {
        int total = 0;
        for (int w : DAILY_DOUBLE_ROW_WEIGHTS) {
            total += w;
        }
        int pick = random.nextInt(total);
        for (int row = 0; row < DAILY_DOUBLE_ROW_WEIGHTS.length; row++) {
            pick -= DAILY_DOUBLE_ROW_WEIGHTS[row];
            if (pick < 0) {
                return row;
            }
        }
        return Game.ROWS - 1;
    }
}
