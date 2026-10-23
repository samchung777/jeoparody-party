package party.jeoparody.trivia;

/** A keyword, its board title (null until generated) and how many clues of each difficulty it covers. */
public record KeywordStats(long id, String name, String title, int total, int easy, int medium, int hard) {
}
