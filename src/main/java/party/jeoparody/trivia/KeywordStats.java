package party.jeoparody.trivia;

/** A keyword with how many enriched clues of each difficulty it covers. */
public record KeywordStats(long id, String name, int total, int easy, int medium, int hard) {
}
