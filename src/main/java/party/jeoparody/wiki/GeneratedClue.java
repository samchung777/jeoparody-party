package party.jeoparody.wiki;

import java.util.List;

/** A clue Gemini wrote from an article, with the sentence that proves it. */
public record GeneratedClue(String difficulty, String clue, String response, String answer, String quote,
        List<String> keywords) {
}
