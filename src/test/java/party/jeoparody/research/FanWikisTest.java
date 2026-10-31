package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): the fan-wiki allow-list. Only listed wikis are used, each for its own keywords,
 * and blocked hosts (e.g. Fandom, when blocked-hosts lists it) are refused even if someone lists them.
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

    @Test
    @SuppressWarnings("unchecked")
    void theShippedListParsesWithoutProblemsAndIncludesFandom() throws Exception {
        Map<String, Object> yml;
        try (var in = getClass().getResourceAsStream("/application.yml")) {
            yml = new org.yaml.snakeyaml.Yaml().load(in);
        }
        Map<String, Object> research = (Map<String, Object>) ((Map<String, Object>) yml.get("trivia")).get("research");
        FanWikis wikis = new FanWikis((List<String>) research.get("fan-wikis"), List.of());
        assertEquals(List.of(), wikis.rejected());
        assertTrue(wikis.entries().size() > 100);
        assertEquals("bulbapedia.bulbagarden.net", wikis.forKeyword("Pokemon Games").orElseThrow().getHost());
        assertEquals("naruto.fandom.com", wikis.forKeyword("naruto").orElseThrow().getHost());
        assertEquals("pixar.fandom.com", wikis.forKeyword("WALL-E").orElseThrow().getHost());
    }
}
