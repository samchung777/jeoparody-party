package party.jeoparody.review;

/** A live clue waiting to be checked for behind-the-scenes content. */
public record ClueToReview(long id, String category, String clue, String response) {
}
