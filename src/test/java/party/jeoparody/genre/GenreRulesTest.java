package party.jeoparody.genre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): the deterministic genre rules tried before Gemini.
 * Name rules, the Open Trivia DB majority, and the cases that must be left for Gemini.
 */
class GenreRulesTest {

    @Test
    void namesThatSettleTheGenre() {
        assertEquals(Set.of("Anime & Manga"), GenreRules.fromName("Shounen Anime"));
        assertEquals(Set.of("Cartoons"), GenreRules.fromName("90s Cartoons"));
        assertEquals(Set.of("Musicals & Theatre"), GenreRules.fromName("Broadway Musicals"));
        assertEquals(Set.of("Bible & Mythology"), GenreRules.fromName("Old Testament"));
        assertEquals(Set.of("Video Games"), GenreRules.fromName("Modern Video Games"));
        assertEquals(Set.of("Movies", "Music"), GenreRules.fromName("Disney Movie Songs"), "both can apply");
    }

    @Test
    void wordsMustMatchWholeNotInsideOtherWords() {
        assertTrue(GenreRules.fromName("Bookkeeping").isEmpty(), "'book' inside a longer word doesn't count");
        assertTrue(GenreRules.fromName("Animal Crossing").isEmpty(), "'anime' isn't 'Animal'");
        assertTrue(GenreRules.fromName("Naruto").isEmpty());
        assertTrue(GenreRules.fromName(null).isEmpty());
    }

    @Test
    void mostlyOneOpenTriviaCategoryDecides() {
        assertEquals(Set.of("Movies"), GenreRules.pick("Time Travel", Map.of("Film", 7, "Television", 3)),
                "70% Film");
        assertEquals(Set.of("Movies"), GenreRules.pick("Time Travel", Map.of("Film", 6, "Television", 4)),
                "exactly 60% is enough");
        assertTrue(GenreRules.pick("Time Travel", Map.of("Film", 5, "Television", 5)).isEmpty(), "a tie goes to Gemini");
        assertTrue(GenreRules.pick("Naruto", Map.of("Naruto (Wikipedia)", 20)).isEmpty(),
                "Wikipedia-sourced keywords go to Gemini");
        assertTrue(GenreRules.pick("Empty", Map.of()).isEmpty());
    }

    @Test
    void theNameWinsOverWhereTheCluesCameFrom() {
        assertEquals(Set.of("Anime & Manga"), GenreRules.pick("Anime Villains", Map.of("Television", 10)));
    }
}
