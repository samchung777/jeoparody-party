package party.jeoparody.game;

/**
 * A clue that could go on the board for a keyword. {@code staleness} counts how many games have already shown
 * it; BoardBuilder prefers the freshest clues so regular players don't see repeats.
 */
public record CandidateClue(long id, String difficulty, String clue, String response, int staleness) {

    /** A clue that has never been shown. */
    public CandidateClue(long id, String difficulty, String clue, String response) {
        this(id, difficulty, clue, response, 0);
    }
}
