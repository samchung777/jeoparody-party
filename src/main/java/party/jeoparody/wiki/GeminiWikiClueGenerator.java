package party.jeoparody.wiki;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;

import party.jeoparody.enrich.GeminiClient;

/** Writes clues from an article with Gemini, then keeps only the verifiable ones. */
@Component
public class GeminiWikiClueGenerator implements WikiClueGenerator {

    private final GeminiClient gemini;

    public GeminiWikiClueGenerator(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public List<GeneratedClue> generate(String topicKeyword, WikiPage page, int count)
            throws IOException, InterruptedException {
        String prompt = WikiCluePrompt.build(topicKeyword, page, count);
        return WikiCluePrompt.parse(gemini.generateJson(prompt, WikiCluePrompt.responseSchema(gemini.mapper())),
                page, topicKeyword);
    }
}
