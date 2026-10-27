package party.jeoparody.review;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import party.jeoparody.enrich.GeminiClient;

/** Production ClueReviewer: asks Gemini which clues in a batch are behind-the-scenes trivia. */
@Component
public class GeminiClueReviewer implements ClueReviewer {

    private final GeminiClient gemini;

    /** Built on the shared GeminiClient. */
    public GeminiClueReviewer(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /** Returns clue id -> reason for each clue to retire. */
    @Override
    public Map<Long, String> offTopic(List<ClueToReview> batch) throws IOException, InterruptedException {
        return ReviewPrompt.parse(gemini.generateJson(ReviewPrompt.build(gemini.mapper(), batch),
                ReviewPrompt.responseSchema(gemini.mapper())), batch);
    }
}
