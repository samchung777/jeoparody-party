package party.jeoparody.review;

/**
 * A live clue waiting to be checked for behind-the-scenes content. {@code genres} are the general categories of
 * its keywords ("Anime & Manga|Movies"), so the reviewer knows whether actor clues are allowed.
 */
public record ClueToReview(long id, String category, String clue, String response, String genres) {

    /** A clue with no known genres. */
    public ClueToReview(long id, String category, String clue, String response) {
        this(id, category, clue, response, "");
    }
}
