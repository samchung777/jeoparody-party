package party.jeoparody.review;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import party.jeoparody.enrich.GeminiClient;

@Component
public class GeminiClueReviewer implements ClueReviewer {

    private final GeminiClient gemini;

    public GeminiClueReviewer(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public Map<Long, String> offTopic(List<ClueToReview> batch) throws IOException, InterruptedException {
        return ReviewPrompt.parse(gemini.generateJson(ReviewPrompt.build(gemini.mapper(), batch),
                ReviewPrompt.responseSchema(gemini.mapper())), batch);
    }
}
