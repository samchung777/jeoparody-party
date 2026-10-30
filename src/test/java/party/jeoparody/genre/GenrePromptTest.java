package party.jeoparody.genre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import party.jeoparody.game.Themes;

/** Unit test (Surefire, `*Test`): the genre prompt, its enum schema, and parsing of Gemini's reply. */
class GenrePromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<KeywordToClassify> batch = List.of(
            new KeywordToClassify(1, "Naruto", List.of("Who is Sasuke?")),
            new KeywordToClassify(2, "Hamilton", List.of("Who is Aaron Burr?")));

    @Test
    void promptListsTheGenresAndKeywords() {
        String prompt = GenrePrompt.build(mapper, batch);
        assertTrue(prompt.contains(String.join(", ", Themes.GENRES)));
        assertTrue(prompt.contains("Who is Sasuke?"));
    }

    @Test
    void schemaLimitsGenresToTheFixedList() {
        var allowed = GenrePrompt.responseSchema(mapper).path("items").path("properties").path("genres")
                .path("items").path("enum");
        assertEquals(Themes.GENRES.size(), allowed.size());
    }

    @Test
    void keepsKnownGenresForRequestedKeywordsOnly() throws Exception {
        var reply = mapper.readTree("""
                [{"id":1,"genres":["Anime & Manga","Sports","TV","Books"]},
                 {"id":1,"genres":["Movies"]},
                 {"id":2,"genres":[]},
                 {"id":99,"genres":["Movies"]}]""");
        Map<Long, Set<String>> out = GenrePrompt.parse(reply, batch);
        assertEquals(Set.of("Anime & Manga", "TV"), out.get(1L), "unknown genre dropped, max 2, first answer wins");
        assertEquals(Set.of(), out.get(2L), "an empty answer still counts as checked");
        assertEquals(2, out.size(), "id 99 wasn't asked about");
    }
}
