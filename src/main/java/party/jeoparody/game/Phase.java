package party.jeoparody.game;

/** The stages a game moves through, in order. */
public enum Phase {
    /** Teams join and draw their names. */
    LOBBY,
    /** Teams vote for the 12 board categories from a random keyword pool. */
    VOTING,
    /** Teams vote for the Final Jeopardy category from the keywords left over. */
    FINAL_VOTING,
    /** The host picks the next clue from the board. */
    BOARD,
    /** The team in control enters a Daily Double wager. */
    DAILY_DOUBLE_WAGER,
    /** A clue is on screen (buzzers, answering, reveal). */
    CLUE,
    /** Final Jeopardy category is shown; eligible teams wager. */
    FINAL_WAGER,
    /** Final Jeopardy clue is shown; teams write their responses. */
    FINAL_ANSWER,
    /** The host reveals and judges each team's Final Jeopardy response. */
    FINAL_REVEAL,
    /** Scores are final. */
    GAME_OVER
}
