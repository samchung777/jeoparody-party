package party.jeoparody.genre;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import party.jeoparody.enrich.GeminiClient;

/** Production GenreClassifier: GenrePrompt + the shared GeminiClient. */
@Component
public class GeminiGenreClassifier implements GenreClassifier {

    private final GeminiClient gemini;

    public GeminiGenreClassifier(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public Map<Long, Set<String>> classify(List<KeywordToClassify> keywords) throws IOException, InterruptedException {
        return GenrePrompt.parse(gemini.generateJson(GenrePrompt.build(gemini.mapper(), keywords),
                GenrePrompt.responseSchema(gemini.mapper())), keywords);
    }
}
