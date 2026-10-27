package party.jeoparody.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Checks parsing and cleaning of Gemini's category titles.
 */
class TitlePromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<KeywordToTitle> keywords = List.of(
            new KeywordToTitle(10, "Star Wars", List.of("Who is Yoda?", "What is Tatooine?")),
            new KeywordToTitle(11, "80s Movies", List.of("What is Ghostbusters?")));

    @Test
    void promptListsKeywordsWithSamples() {
        String prompt = TitlePrompt.build(mapper, keywords);
        assertTrue(prompt.contains("Star Wars"));
        assertTrue(prompt.contains("What is Tatooine?"));
    }

    @Test
    void keepsValidTitlesForRequestedKeywordsOnly() throws Exception {
        var reply = mapper.readTree("""
                [{"id":10,"title":"  May The   Quiz Be With You "},
                 {"id":11,"title":"This title is far too long to fit on a Jeopardy board square"},
                 {"id":12,"title":"Not requested"}]""");
        assertEquals(Map.of(10L, "May The Quiz Be With You"), TitlePrompt.parse(reply, keywords));
    }
}
