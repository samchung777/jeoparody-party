package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit test (Surefire, `*Test`): turning fan-wiki wikitext into plain text the clue writer can quote. */
class FanWikiTextTest {

    @Test
    void markupIsRemovedAndLinkTextKept() {
        String wikitext = """
                {{Infobox character|name=Nick Campbell|power={{Tooltip|Wild Card}}}}
                '''Nick Campbell''' is a [[Powered]] student at [[Lander University|Lander]].<ref>Book 1</ref>
                [[File:Nick.png|thumb|Nick at [[Lander University|Lander]]]]
                == Personality ==
                * He is sarcastic.<!-- editors: keep this short -->
                He bets on [https://example.org card games] often.__NOTOC__
                {| class="wikitable"
                | Stat || Value
                |}
                [[Category:Characters]]
                """;
        String clean = FanWikiText.clean(wikitext);
        assertTrue(clean.startsWith("Nick Campbell is a Powered student at Lander."), clean);
        assertTrue(clean.contains("== Personality =="), "headings are kept for section rules");
        assertTrue(clean.contains("He is sarcastic."));
        assertTrue(clean.contains("He bets on card games often."));
        assertFalse(clean.contains("Infobox"));
        assertFalse(clean.contains("Book 1"), "references removed");
        assertFalse(clean.contains("thumb"), "file captions removed");
        assertFalse(clean.contains("Stat"), "tables removed");
        assertFalse(clean.contains("Category"));
        assertFalse(clean.contains("editors"), "comments removed");
        assertFalse(clean.contains("NOTOC"));
    }

    @Test
    void emptyInputGivesEmptyText() {
        assertEquals("", FanWikiText.clean(null));
        assertEquals("", FanWikiText.clean("   "));
        assertEquals("", FanWikiText.clean("{{only a template}}"));
    }
}
