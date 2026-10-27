package party.jeoparody.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Parses saved Wikipedia API responses: articles, character lists, skipped pages.
 */
class MediaWikiSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String LONG_TEXT = "Fire Emblem is a fantasy tactical role-playing game franchise. ".repeat(20);

    private Optional<WikiPage> parse(String pageJson) throws Exception {
        return MediaWikiSource.parsePage(mapper.readTree("{\"query\":{\"pages\":[" + pageJson + "]}}"), 60_000);
    }

    @Test
    void parsesAnArticle() throws Exception {
        Optional<WikiPage> page = parse("""
                {"pageid":11123,"title":"Fire Emblem","fullurl":"https://en.wikipedia.org/wiki/Fire_Emblem",
                 "extract":"%s\\n\\n== References ==\\nCitations"}""".formatted(LONG_TEXT));
        assertTrue(page.isPresent());
        assertEquals(11123, page.get().pageId());
        assertEquals("https://en.wikipedia.org/wiki/Fire_Emblem", page.get().url());
        assertTrue(!page.get().text().contains("Citations"));
    }

    @Test
    void characterListsAreAppendedWithinTheBudget() {
        WikiPage main = new WikiPage(1, "Naruto", "https://en.wikipedia.org/wiki/Naruto", "Naruto Uzumaki is a ninja. ".repeat(100));
        WikiPage cast = new WikiPage(2, "List of Naruto characters", "u", "Sasuke Uchiha is his rival. ".repeat(100));
        WikiPage combined = MediaWikiSource.combine(main, cast, 3000);
        assertTrue(combined.text().length() <= 3000);
        assertTrue(combined.text().contains("Characters (from \"List of Naruto characters\"):"));
        assertTrue(combined.text().contains("Sasuke Uchiha"));
        assertEquals("https://en.wikipedia.org/wiki/Naruto", combined.url(), "the main article stays the source");
    }

    @Test
    void skipsMissingDisambiguationAndStubPages() throws Exception {
        assertTrue(parse("{\"title\":\"Nope\",\"missing\":true}").isEmpty());
        assertTrue(parse("""
                {"pageid":1,"title":"Harvest Moon","pageprops":{"disambiguation":""},"extract":"%s"}"""
                .formatted(LONG_TEXT)).isEmpty());
        assertTrue(parse("{\"pageid\":2,\"title\":\"Stub\",\"extract\":\"Too short.\"}").isEmpty());
        assertTrue(MediaWikiSource.parsePage(mapper.readTree("{}"), 60_000).isEmpty());
    }
}
