package party.jeoparody.game;

/** One square on the board. */
public final class BoardCell {

    private final int column;
    private final int row;
    private final int value;
    private final CandidateClue clue;
    private boolean dailyDouble;
    private boolean used;

    BoardCell(int column, int row, int value, CandidateClue clue) {
        this.column = column;
        this.row = row;
        this.value = value;
        this.clue = clue;
    }

    public int column() { return column; }
    public int row() { return row; }
    public int value() { return value; }
    public CandidateClue clue() { return clue; }
    public boolean dailyDouble() { return dailyDouble; }
    public boolean used() { return used; }

    void markDailyDouble() { dailyDouble = true; }
    void markUsed() { used = true; }
}
