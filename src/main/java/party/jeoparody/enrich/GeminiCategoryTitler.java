package party.jeoparody.enrich;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Writes punny Jeopardy!-style category titles with Gemini. */
@Component
public class GeminiCategoryTitler implements CategoryTitler {

    private final GeminiClient gemini;

    public GeminiCategoryTitler(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @Override
    public Map<Long, String> title(List<KeywordToTitle> keywords) throws IOException, InterruptedException {
        String prompt = TitlePrompt.build(gemini.mapper(), keywords);
        return TitlePrompt.parse(gemini.generateJson(prompt, TitlePrompt.responseSchema(gemini.mapper())), keywords);
    }
}
