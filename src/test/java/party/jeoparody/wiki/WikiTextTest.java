package party.jeoparody.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WikiTextTest {

    @Test
    void topicsDropDisambiguationFromTheKeyword() {
        WikiText.TopicSpec spec = WikiText.parseTopic("  Hamilton   (musical) ");
        assertEquals("Hamilton", spec.keyword());
        assertEquals("Hamilton (musical)", spec.pageQuery());
    }

    @Test
    void hostCanNameTheExactPage() {
        WikiText.TopicSpec spec = WikiText.parseTopic("Harvest Moon = Harvest Moon (video game series)");
        assertEquals("Harvest Moon", spec.keyword());
        assertEquals("Harvest Moon (video game series)", spec.pageQuery());
        assertEquals("Fire Emblem", WikiText.parseTopic("Fire Emblem =").pageQuery());
    }

    @Test
    void keywordsAreCleaned() {
        assertEquals("Clair Obscur Expedition 33", WikiText.parseTopic("Clair Obscur: Expedition 33").keyword());
        assertEquals("Pokémon", WikiText.keyword("Pokémon!"));
        assertTrue(WikiText.keyword("x".repeat(80)).length() <= WikiText.MAX_KEYWORD_LENGTH);
    }

    @Test
    void articleCleanupDropsReferenceSectionsAndFormatsHeadings() {
        String extract = """
                Hamilton is a sung-and-rapped-through musical.

                == Synopsis ==
                Alexander Hamilton arrives in New York.


                === Act I ===
                He meets Aaron Burr.

                == See also ==
                Other musicals.

                == References ==
                [1] Some citation.
                """;
        String clean = WikiText.cleanArticle(extract, 10_000);
        assertTrue(clean.contains("Synopsis:"));
        assertTrue(clean.contains("Act I:"));
        assertTrue(clean.contains("He meets Aaron Burr."));
        assertFalse(clean.contains("Other musicals"));
        assertFalse(clean.contains("citation"));
        assertFalse(clean.contains("\n\n\n"));
    }

    @Test
    void longArticlesAreCutAtASentence() {
        String text = "First sentence here. ".repeat(100);
        String clean = WikiText.cleanArticle(text, 105);
        assertTrue(clean.length() <= 105);
        assertTrue(clean.endsWith("."));
    }

    @Test
    void quotesMatchDespiteTypography() {
        String article = WikiText.normalize("The show premiered Off-Broadway at The Public Theater in February 2015 — "
                + "it was “the hottest ticket” in town.");
        assertTrue(WikiText.quoteAppearsIn("\"The show premiered Off-Broadway at the Public Theater in February 2015.\"",
                article, 20));
        assertTrue(WikiText.quoteAppearsIn("it was \"the hottest ticket\" in town", article, 20));
        assertFalse(WikiText.quoteAppearsIn("The show premiered on Broadway in 2014", article, 20), "invented fact");
        assertFalse(WikiText.quoteAppearsIn("in town", article, 20), "too short to prove anything");
    }

    @Test
    void detectsAnswersGivenAwayInTheClue() {
        assertTrue(WikiText.givesAwayAnswer("Lin-Manuel Miranda wrote this musical about Hamilton", "Hamilton"));
        assertFalse(WikiText.givesAwayAnswer("This Burr shot him", "Aaron Burr"));
        assertTrue(WikiText.givesAwayAnswer("This Aaron Burr song...", "The Aaron Burr"));
        assertFalse(WikiText.givesAwayAnswer("This man shot Hamilton in a duel", "Aaron Burr"));
        assertFalse(WikiText.givesAwayAnswer("This is about one", "One"), "short answers ignored");
    }

    @Test
    void answerKeysIgnoreArticlesCaseAndPunctuation() {
        assertEquals(WikiText.answerKey("The Legend of Zelda"), WikiText.answerKey("legend of zelda!"));
        assertEquals("pokémon", WikiText.answerKey("Pokémon"));
    }
}
