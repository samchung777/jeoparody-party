package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): turning a pasted fan wiki link into a page title and api.php locations.
 */
class FanWikiPageLinkTest {

    @Test
    void fandomLinkGivesTheTitleAndTriesApiPhpAtTheRootFirst() {
        FanWikiPageLink link = FanWikiPageLink.parse("https://naruto.fandom.com/wiki/Itachi_Uchiha#Personality");
        assertEquals("Itachi Uchiha", link.title());
        assertEquals(URI.create("https://naruto.fandom.com/api.php"), link.apiCandidates().get(0));
        assertTrue(link.apiCandidates().contains(URI.create("https://naruto.fandom.com/w/api.php")));
    }

    @Test
    void encodedTitlesLanguagePathsAndIndexPhpLinksWork() {
        assertEquals("Pokémon Red and Blue",
                FanWikiPageLink.parse("https://bulbapedia.bulbagarden.net/wiki/Pok%C3%A9mon_Red_and_Blue").title());
        FanWikiPageLink fr = FanWikiPageLink.parse("https://naruto.fandom.com/fr/wiki/Itachi_Uchiwa");
        assertEquals(URI.create("https://naruto.fandom.com/fr/api.php"), fr.apiCandidates().get(0));
        FanWikiPageLink index = FanWikiPageLink.parse(
                "https://stardewvalleywiki.com/mediawiki/index.php?title=Abigail&oldid=1");
        assertEquals("Abigail", index.title());
        assertEquals(URI.create("https://stardewvalleywiki.com/mediawiki/api.php"), index.apiCandidates().get(0));
    }

    @Test
    void nonArticlesAndBadLinksAreRefusedWithAMessage() {
        assertEquals("The link must start with https://",
                assertThrows(IllegalArgumentException.class,
                        () -> FanWikiPageLink.parse("http://naruto.fandom.com/wiki/Naruto")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> FanWikiPageLink.parse("https://naruto.fandom.com/"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FanWikiPageLink.parse("https://naruto.fandom.com/wiki/Category:Characters")).getMessage()
                .contains("Category page"));
        assertThrows(IllegalArgumentException.class, () -> FanWikiPageLink.parse(" "));
    }
}
