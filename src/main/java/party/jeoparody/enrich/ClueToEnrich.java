package party.jeoparody.enrich;

/**
 * A raw Open Trivia DB clue waiting to be rewritten and tagged.
 * {@code hostFeedback} is set when a host asked for a rewrite ("too wordy", "gives away the answer"...).
 */
public record ClueToEnrich(long id, String category, String difficulty, String question, String answer,
        String hostFeedback) {

    /** Convenience constructor for a clue with no host feedback. */
    public ClueToEnrich(long id, String category, String difficulty, String question, String answer) {
        this(id, category, difficulty, question, answer, null);
    }
}
