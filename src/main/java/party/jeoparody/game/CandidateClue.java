package party.jeoparody.game;

import java.util.List;

/**
 * A clue that could go on the board for a keyword. {@code staleness} counts how many games have already shown
 * it; BoardBuilder prefers the freshest clues so regular players don't see repeats. {@code keywords} are every
 * subject the clue is tagged with (the screens show the ones that don't give the answer away, see ClueTags).
 */
public record CandidateClue(long id, String difficulty, String clue, String response, int staleness,
        List<String> keywords) {

    /** Copies the keyword list so the record stays immutable. */
    public CandidateClue {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /** A clue without keyword tags. */
    public CandidateClue(long id, String difficulty, String clue, String response, int staleness) {
        this(id, difficulty, clue, response, staleness, List.of());
    }

    /** A clue that has never been shown. */
    public CandidateClue(long id, String difficulty, String clue, String response) {
        this(id, difficulty, clue, response, 0, List.of());
    }
}
