package party.jeoparody.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WikiCluePromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WikiPage page = new WikiPage(42, "Hamilton (musical)", "https://en.wikipedia.org/wiki/Hamilton_(musical)",
            "Hamilton is a sung-and-rapped-through musical with music, lyrics, and book by Lin-Manuel Miranda. "
                    + "It tells the story of American Founding Father Alexander Hamilton. "
                    + "The show premiered Off-Broadway at The Public Theater in February 2015. "
                    + "Aaron Burr shot Hamilton in a duel in 1804.");

    private final WikiText.TopicSpec topic = WikiText.parseTopic("Hamilton = Hamilton (musical) [Broadway Musicals]");

    @Test
    void promptContainsTopicArticleCountStoryRulesAndExclusions() {
        String prompt = WikiCluePrompt.build(WikiText.parseTopic("Hamilton {focus on the Schuyler sisters}"), page, 24,
                Set.of("aaron burr"));
        assertTrue(prompt.contains("Write up to 24 clues"));
        assertTrue(prompt.contains("\"Hamilton\""));
        assertTrue(prompt.contains("Article title: Hamilton (musical)"));
        assertTrue(prompt.contains("Aaron Burr shot Hamilton in a duel in 1804."));
        assertTrue(prompt.contains("NEVER WRITE ABOUT"));
        assertTrue(prompt.contains("The host asked for this focus: focus on the Schuyler sisters"));
        assertTrue(prompt.contains("already used; do not use them again: aaron burr"));
        assertTrue(!WikiCluePrompt.build(topic, page, 24, Set.of()).contains("already used"));
    }

    @Test
    void onlyVerifiableNonGiveawayCluesSurvive() throws Exception {
        var reply = mapper.readTree("""
                [
                 {"difficulty":"easy","clue":"This man wrote the music, lyrics and book of the show",
                  "answer":"Lin-Manuel Miranda","response":"Who is Lin-Manuel Miranda?",
                  "quote":"with music, lyrics, and book by Lin-Manuel Miranda",
                  "keywords":["Composers","Hamilton","Lin-Manuel Miranda"]},
                 {"difficulty":"hard","clue":"The show won this many Tonys","answer":"Eleven","response":"What is eleven?",
                  "quote":"The show won eleven Tony Awards in 2016 including Best Musical.","keywords":[]},
                 {"difficulty":"medium","clue":"Aaron Burr shot this man in 1804","answer":"Aaron Burr",
                  "response":"Who is Aaron Burr?","quote":"Aaron Burr shot Hamilton in a duel in 1804.","keywords":[]},
                 {"difficulty":"easy","clue":"This musical tells a Founding Father's story","answer":"Hamilton",
                  "response":"What is Hamilton?","quote":"It tells the story of American Founding Father Alexander Hamilton.",
                  "keywords":[]},
                 {"difficulty":"easy","clue":"This creator also starred","answer":"lin-manuel miranda!",
                  "response":"Who is Lin-Manuel Miranda?",
                  "quote":"with music, lyrics, and book by Lin-Manuel Miranda","keywords":[]},
                 {"difficulty":"trivial","clue":"x","answer":"y","response":"What is y?","quote":"z","keywords":[]},
                 {"difficulty":"Medium","clue":"The show premiered Off-Broadway at this downtown venue in 2015",
                  "answer":"The Public Theater","response":"The Public Theater",
                  "quote":"\\u201CThe show premiered Off-Broadway at The Public Theater in February 2015.\\u201D",
                  "keywords":["Off-Broadway Venues"]}
                ]""");
        List<GeneratedClue> clues = WikiCluePrompt.parse(reply, page, topic, Set.of());
        assertEquals(2, clues.size(), "invented, giveaway, topic-as-answer, duplicate and malformed clues are dropped");

        GeneratedClue first = clues.get(0);
        assertEquals("Lin-Manuel Miranda", first.answer());
        assertEquals(List.of("Hamilton", "Broadway Musicals", "Composers"), first.keywords(),
                "topic, then groups, then narrower; the answer and repeats removed");

        GeneratedClue second = clues.get(1);
        assertEquals("medium", second.difficulty());
        assertEquals("What is The Public Theater?", second.response(), "response fixed into question form");
        assertEquals(List.of("Hamilton", "Broadway Musicals", "Off-Broadway Venues"), second.keywords());
    }

    @Test
    void answersAlreadyUsedForThePageAreSkipped() throws Exception {
        var reply = mapper.readTree("""
                [{"difficulty":"easy","clue":"This man wrote the music, lyrics and book of the show",
                  "answer":"Lin-Manuel Miranda","response":"Who is Lin-Manuel Miranda?",
                  "quote":"with music, lyrics, and book by Lin-Manuel Miranda","keywords":[]}]""");
        assertTrue(WikiCluePrompt.parse(reply, page, topic, Set.of(WikiText.answerKey("Lin-Manuel Miranda"))).isEmpty());
    }

    @Test
    void schemaRequiresAQuote() {
        var schema = WikiCluePrompt.responseSchema(mapper);
        assertTrue(schema.path("items").path("required").toString().contains("quote"));
    }
}
