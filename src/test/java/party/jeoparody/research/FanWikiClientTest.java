package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import party.jeoparody.wiki.WikiPage;

/** Unit test (Surefire, `*Test`): reading the MediaWiki API's search and page replies from a fan wiki. */
class FanWikiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BODY = "Nick Campbell is a student at Lander University who can bend luck. ".repeat(20);

    @Test
    void searchTitlesKeepRankOrder() throws Exception {
        var root = mapper.readTree("""
                {"query":{"search":[{"title":"Nick Campbell"},{"title":""},{"title":"Lander University"}]}}""");
        assertEquals(List.of("Nick Campbell", "Lander University"), FanWikiClient.searchTitles(root));
        assertTrue(FanWikiClient.searchTitles(mapper.readTree("{}")).isEmpty());
    }

    @Test
    void pagesAreCleanedOrderedBySearchRankAndStubsSkipped() throws Exception {
        var root = mapper.readTree("""
                {"query":{"pages":[
                  {"pageid":2,"title":"Lander University","fullurl":"https://w.example/wiki/Lander",
                   "revisions":[{"slots":{"main":{"content":"'''Lander''' is a school. %s"}}}]},
                  {"pageid":1,"title":"Nick Campbell","fullurl":"https://w.example/wiki/Nick",
                   "revisions":[{"slots":{"main":{"content":"{{Infobox}}%s"}}}]},
                  {"pageid":3,"title":"Stub","revisions":[{"slots":{"main":{"content":"Too short."}}}]},
                  {"title":"Missing","missing":true}]}}""".formatted(BODY, BODY));
        List<FanWikiClient.FanPage> pages = FanWikiClient.parsePages(root, List.of("Nick Campbell", "Lander University"));
        assertEquals(List.of("Nick Campbell", "Lander University"), pages.stream().map(FanWikiClient.FanPage::title).toList());
        assertTrue(pages.get(1).text().startsWith("Lander is a school."));
    }

    @Test
    void combinedPageCitesTheTopResultAndStaysWithinBudget() {
        List<FanWikiClient.FanPage> pages = List.of(
                new FanWikiClient.FanPage(1, "Nick Campbell", "https://w.example/wiki/Nick", BODY),
                new FanWikiClient.FanPage(2, "Lander University", "https://w.example/wiki/Lander", BODY));
        WikiPage page = FanWikiClient.combine(pages, 4000).orElseThrow();
        assertEquals("https://w.example/wiki/Nick", page.url());
        assertEquals("Nick Campbell (fan wiki)", page.title());
        assertTrue(page.text().length() <= 4000);
        assertTrue(page.text().contains("== Lander University =="));
        assertTrue(FanWikiClient.combine(List.of(), 4000).isEmpty());
    }

    @Test
    void aSinglePastedPageKeepsItsStorySectionsAndSkipsStubs() throws Exception {
        String story = "Itachi Uchiha was a prodigy of the Uchiha clan who joined the Akatsuki. ".repeat(12);
        var root = mapper.readTree("""
                {"query":{"pages":[{"pageid":5,"title":"Itachi Uchiha","fullurl":"https://naruto.fandom.com/wiki/Itachi_Uchiha",
                  "revisions":[{"slots":{"main":{"content":"%s"}}}]}]}}""".formatted(story));
        var page = FanWikiClient.singlePage(root, 60000).orElseThrow();
        assertEquals("Itachi Uchiha (fan wiki)", page.title());
        assertEquals("https://naruto.fandom.com/wiki/Itachi_Uchiha", page.url());
        assertTrue(page.text().contains("Akatsuki"));
        assertTrue(FanWikiClient.singlePage(mapper.readTree("""
                {"query":{"pages":[{"title":"Nope","missing":true}]}}"""), 60000).isEmpty());
    }
}
