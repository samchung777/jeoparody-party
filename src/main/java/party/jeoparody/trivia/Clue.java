package party.jeoparody.trivia;

/** A stored clue. In Jeopardy terms, {@code question} is shown on the board and {@code answer} is the correct response. */
public record Clue(long id, long categoryId, String difficulty, String question, String answer) {
}
