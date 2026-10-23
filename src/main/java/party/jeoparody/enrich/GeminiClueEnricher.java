package party.jeoparody.enrich;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;

/** Rewrites clues into Jeopardy form and tags keywords using Gemini. */
@Component
public class GeminiClueEnricher implements ClueEnricher {

    private final GeminiClient gemini;

    public GeminiClueEnricher(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public List<EnrichedClue> enrich(List<ClueToEnrich> batch, List<String> existingKeywords)
            throws IOException, InterruptedException {
        String prompt = EnrichmentPrompt.build(gemini.mapper(), batch, existingKeywords);
        return EnrichmentPrompt.parse(gemini.generateJson(prompt, EnrichmentPrompt.responseSchema(gemini.mapper())), batch);
    }
}
