package party.jeoparody.trivia;

/** One usable question parsed (and URL-decoded) from an Open Trivia DB response. */
public record ParsedQuestion(String category, String difficulty, String question, String answer) {
}
