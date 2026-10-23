package party.jeoparody.enrich;

/** A raw Open Trivia DB clue waiting to be rewritten and tagged. */
public record ClueToEnrich(long id, String category, String difficulty, String question, String answer) {
}
