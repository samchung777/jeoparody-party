package party.jeoparody.trivia;

import java.util.List;

/**
 * A stored clue. {@code question}/{@code answer} are the original Open Trivia DB text;
 * {@code jeopardyClue}/{@code jeopardyResponse} are the Gemini rewrite (null until enriched).
 */
public record Clue(
        long id,
        long categoryId,
        String difficulty,
        String question,
        String answer,
        String jeopardyClue,
        String jeopardyResponse,
        List<String> keywords) {
}
