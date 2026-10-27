package party.jeoparody.game;

/** Board rounds and their clue values. */
public enum Round {
    /**
     * Round 1: $200-$1000, one Daily Double. Round 2 (Double Jeopardy): $400-$2000, two Daily Doubles.
     */
    JEOPARDY(200, 1),
    DOUBLE_JEOPARDY(400, 2);

    private final int baseValue;
    private final int dailyDoubles;

    Round(int baseValue, int dailyDoubles) {
        this.baseValue = baseValue;
        this.dailyDoubles = dailyDoubles;
    }

    /** Value of a clue in row 0-4: $200-$1000 or $400-$2000. */
    public int value(int row) {
        return baseValue * (row + 1);
    }

    /** The largest clue value on the board, used as the Daily Double wager cap for low scorers. */
    public int maxValue() {
        return value(Game.ROWS - 1);
    }

    /** How many Daily Doubles BoardBuilder hides on this round's board. */
    public int dailyDoubles() {
        return dailyDoubles;
    }
}
