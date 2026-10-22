package party.jeoparody.trivia;

/** A trivia category with how many clues of each difficulty are stored. */
public record Category(long id, String name, int easy, int medium, int hard) {
}
