package party.jeoparody.game;

import java.util.List;

/** The categories and cells for one round. */
public record Board(Round round, List<BoardCategory> categories) {

    public BoardCell cell(int column, int row) {
        if (column < 0 || column >= categories.size() || row < 0 || row >= Game.ROWS) {
            throw new GameException("No such square");
        }
        return categories.get(column).cells().get(row);
    }

    public boolean allUsed() {
        return categories.stream().flatMap(c -> c.cells().stream()).allMatch(BoardCell::used);
    }
}
