package party.jeoparody.game;

/** A clue that could go on the board for a keyword. */
public record CandidateClue(long id, String difficulty, String clue, String response) {
}
