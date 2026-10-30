package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import party.jeoparody.wiki.WikiPage;

/** Unit test (Surefire, `*Test`): combining Wikipedia and a fan wiki into one source for the clue writer. */
class ResearchServiceTest {

    @Test
    void wikipediaComesFirstAndStaysTheSourceOfRecord() {
        WikiPage wiki = new WikiPage(1, "Super Powereds", "https://en.wikipedia.org/wiki/Super_Powereds",
                "Super Powereds is a novel series by Drew Hayes. ".repeat(50));
        WikiPage fan = new WikiPage(2, "Nick Campbell (fan wiki)", "https://w.example/wiki/Nick",
                "Nick Campbell can bend luck. ".repeat(100));
        WikiPage merged = ResearchService.merge(wiki, fan, 3000);
        assertEquals("https://en.wikipedia.org/wiki/Super_Powereds", merged.url());
        assertTrue(merged.text().startsWith("Super Powereds is a novel series"));
        assertTrue(merged.text().contains("From the fan wiki (\"Nick Campbell (fan wiki)\")"));
        assertTrue(merged.text().length() <= 3000);
    }
}
