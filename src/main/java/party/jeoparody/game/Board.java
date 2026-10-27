package party.jeoparody.game;

import java.util.List;

/** The categories and cells for one round. */
public record Board(Round round, List<BoardCategory> categories) {

    /**
     * The square the host picked; rejects positions off the board (input comes from the host screen).
     */
    public BoardCell cell(int column, int row) {
        if (column < 0 || column >= categories.size() || row < 0 || row >= Game.ROWS) {
            throw new GameException("No such square");
        }
        return categories.get(column).cells().get(row);
    }

    /** True when every square has been played: Game then moves to the next round. */
    public boolean allUsed() {
        return categories.stream().flatMap(c -> c.cells().stream()).allMatch(BoardCell::used);
    }
}
