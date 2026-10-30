package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): the fan-wiki allow-list. Only listed wikis are used, each for its own keywords,
 * and Fandom is refused even if someone lists it.
 */
class FanWikisTest {

    private static final List<String> BLOCKED = List.of("fandom.com", "wikia.com", "wikia.org");

    @Test
    void listedWikisAreFoundByKeywordIgnoringCaseAndPunctuation() {
        FanWikis wikis = new FanWikis(List.of("Super Powereds, Corpies = https://wiki.example.org/w/api.php"), BLOCKED);
        assertEquals(URI.create("https://wiki.example.org/w/api.php"), wikis.forKeyword("super powereds").orElseThrow());
        assertEquals(URI.create("https://wiki.example.org/w/api.php"), wikis.forKeyword("Corpies!").orElseThrow());
        assertTrue(wikis.forKeyword("Naruto").isEmpty(), "no fan wiki listed: none is used");
        assertTrue(wikis.forKeyword(null).isEmpty());
    }

    @Test
    void fandomAndBadEntriesAreRefused() {
        FanWikis wikis = new FanWikis(List.of(
                "Naruto = https://naruto.fandom.com/api.php",
                "Zelda = https://zelda.wikia.org/api.php",
                "Plain = http://wiki.example.org/api.php",
                "NoApi = https://wiki.example.org/wiki/Main_Page",
                "missing an equals sign"), BLOCKED);
        assertTrue(wikis.entries().isEmpty());
        assertEquals(5, wikis.rejected().size());
        assertTrue(wikis.rejected().get(0).contains("blocked host: fandom.com"));
        assertTrue(wikis.rejected().get(2).contains("https"));
    }

    @Test
    void aHostThatOnlyContainsABlockedNameIsFine() {
        assertEquals(null, FanWikis.problem(URI.create("https://notfandom.community/w/api.php"), BLOCKED));
    }
}
