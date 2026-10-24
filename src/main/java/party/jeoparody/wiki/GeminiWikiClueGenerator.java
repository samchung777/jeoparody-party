package party.jeoparody.wiki;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import party.jeoparody.enrich.GeminiClient;

/** Writes clues from an article with Gemini, then keeps only the verifiable, on-topic ones. */
@Component
public class GeminiWikiClueGenerator implements WikiClueGenerator {

    private final GeminiClient gemini;

    public GeminiWikiClueGenerator(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public List<GeneratedClue> generate(WikiText.TopicSpec topic, WikiPage page, int count, Set<String> excludedAnswers)
            throws IOException, InterruptedException {
        String prompt = WikiCluePrompt.build(topic, page, count, excludedAnswers);
        return WikiCluePrompt.parse(gemini.generateJson(prompt, WikiCluePrompt.responseSchema(gemini.mapper())),
                page, topic, excludedAnswers);
    }
}
