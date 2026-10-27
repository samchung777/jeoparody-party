package party.jeoparody.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Checks topic-line parsing (Keyword = Page [Groups] {count: note}), section filtering and text helpers.
 */
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
        assertEquals("pokemon", WikiText.answerKey("Pokémon"));
    }

    @Test
    void topicsCanCarryGroupsAFocusNoteAndACount() {
        WikiText.TopicSpec spec = WikiText.parseTopic(
                "Disney Songs = Let It Go (Disney song) [Disney Movies, Disney Songs] {8: who sings it and in which film}");
        assertEquals("Disney Songs", spec.keyword());
        assertEquals("Let It Go (Disney song)", spec.pageQuery());
        assertEquals(java.util.List.of("Disney Movies"), spec.groups(), "the keyword itself isn't repeated as a group");
        assertEquals("who sings it and in which film", spec.focus());
        assertEquals(Integer.valueOf(8), spec.count());

        WikiText.TopicSpec plain = WikiText.parseTopic("Naruto [Shonen Anime, Anime]");
        assertEquals("Naruto", plain.keyword());
        assertEquals("Naruto", plain.pageQuery());
        assertEquals(java.util.List.of("Shonen Anime", "Anime"), plain.groups());
        assertEquals(null, plain.focus());
        assertEquals(null, plain.count());
        assertEquals(null, WikiText.parseTopic("Frieren {focus on the elves}").count());
        assertEquals("focus on the elves", WikiText.parseTopic("Frieren {focus on the elves}").focus());
    }

    @Test
    void storyOnlyDropsBehindTheScenesSections() {
        String plot = "Katniss volunteers as tribute in place of her sister Prim. ".repeat(30);
        String extract = "Lead paragraph about the film.\n\n== Plot ==\n" + plot
                + "\n== Cast ==\nJennifer Lawrence as Katniss Everdeen.\n"
                + "== Production ==\nThe director was hired in 2011.\n=== Filming ===\nShot in North Carolina.\n"
                + "== Characters ==\nPeeta Mellark is the baker's son.\n"
                + "== Box office ==\nIt grossed $694 million.\n"
                + "== Critical reception ==\nCritics praised it.\n";
        String story = WikiText.cleanArticle(extract, 100_000, true);
        assertTrue(story.contains("Plot:"));
        assertTrue(story.contains("Jennifer Lawrence as Katniss Everdeen."));
        assertTrue(story.contains("Peeta Mellark is the baker's son."));
        assertFalse(story.contains("director was hired"));
        assertFalse(story.contains("North Carolina"), "subsections of dropped sections go too");
        assertFalse(story.contains("694 million"));
        assertFalse(story.contains("Critics praised"));
        assertTrue(WikiText.cleanArticle(extract, 100_000, false).contains("director was hired"));
    }

    @Test
    void shortStoryTextFallsBackToTheWholeArticle() {
        String extract = "A song from Frozen.\n\n== Background ==\n" + "It was written for Elsa. ".repeat(80)
                + "\n== Reception ==\nIt won awards.\n";
        assertTrue(WikiText.cleanArticle(extract, 100_000, true).contains("written for Elsa"));
    }

    @Test
    void searchResultsMustMatchTheQuery() {
        assertTrue(WikiText.titleMatchesQuery("Spy × Family", "Spy x Family"));
        assertTrue(WikiText.titleMatchesQuery("Frieren: Beyond Journey's End", "Frieren"));
        assertTrue(WikiText.titleMatchesQuery("Let It Go (Disney song)", "Let It Go"));
        assertTrue(WikiText.titleMatchesQuery("Pokémon (video game series)", "Pokemon video game series"));
        assertFalse(WikiText.titleMatchesQuery("Super Mario", "Super Powereds"));
        assertFalse(WikiText.titleMatchesQuery("Superpower (ability)", "Super Powereds"));
        assertTrue(WikiText.titleMatchesQuery("Ender's Game", "Ender's Game"));
    }
}
