package party.jeoparody.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EnrichmentPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClueToEnrich> batch = List.of(
            new ClueToEnrich(1, "Film", "easy", "What 1985 film features a DeLorean?", "Back to the Future"),
            new ClueToEnrich(2, "Musicals", "hard", "Who wrote Hamilton?", "Lin-Manuel Miranda",
                    "The clue gave away the answer. Host note: too easy"));

    @Test
    void promptIncludesItemsExistingKeywordsAndHostFeedback() {
        String prompt = EnrichmentPrompt.build(mapper, batch, List.of("80s Movies", "Broadway Musicals"));
        assertTrue(prompt.contains("80s Movies, Broadway Musicals"));
        assertTrue(prompt.contains("What 1985 film features a DeLorean?"));
        assertTrue(prompt.contains("\"hostFeedback\" : \"The clue gave away the answer. Host note: too easy\""));
    }

    @Test
    void promptSaysNoneYetWithoutKeywords() {
        assertTrue(EnrichmentPrompt.build(mapper, batch, List.of()).contains("(none yet)"));
    }

    @Test
    void schemaDescribesAnArrayOfClues() {
        var schema = EnrichmentPrompt.responseSchema(mapper);
        assertEquals("ARRAY", schema.path("type").asText());
        assertEquals("STRING", schema.path("items").path("properties").path("clue").path("type").asText());
    }

    @Test
    void parsesValidResults() throws Exception {
        var reply = mapper.readTree("""
                [{"id":1,"clue":"This 1985 film sent Marty McFly back in time",
                  "response":"What is Back to the Future?","keywords":["80s Movies","Time Travel","Michael J Fox"]}]""");
        List<EnrichedClue> parsed = EnrichmentPrompt.parse(reply, batch);
        assertEquals(1, parsed.size());
        assertEquals("What is Back to the Future?", parsed.get(0).response());
        assertEquals(List.of("80s Movies", "Time Travel", "Michael J Fox"), parsed.get(0).keywords());
    }

    @Test
    void fixesResponsesThatArentQuestions() throws Exception {
        var reply = mapper.readTree("""
                [{"id":2,"clue":"This man wrote a hip-hop musical","response":"Lin-Manuel Miranda","keywords":["Broadway"]}]""");
        assertEquals("What is Lin-Manuel Miranda?", EnrichmentPrompt.parse(reply, batch).get(0).response());
    }

    @Test
    void cleansKeywords() throws Exception {
        var reply = mapper.readTree("""
                [{"id":1,"clue":"c","response":"What is it?",
                  "keywords":["Back to the Future","80s Movies!","80S MOVIES","Sci-Fi","Time Travel","Extra"]}]""");
        List<String> keywords = EnrichmentPrompt.parse(reply, batch).get(0).keywords();
        // the answer itself is dropped, punctuation stripped, case-insensitive duplicates removed, max 3
        assertEquals(List.of("80s Movies", "Sci-Fi", "Time Travel"), keywords);
    }

    @Test
    void ignoresUnknownIdsDuplicatesAndIncompleteItems() throws Exception {
        var reply = mapper.readTree("""
                [{"id":99,"clue":"c","response":"What is x?","keywords":["A"]},
                 {"id":1,"clue":"","response":"What is x?","keywords":["A"]},
                 {"id":2,"clue":"c","response":"Who is x?","keywords":[]}]""");
        assertTrue(EnrichmentPrompt.parse(reply, batch).isEmpty());
    }

    @Test
    void eachIdIsUsedOnce() throws Exception {
        var reply = mapper.readTree("""
                [{"id":1,"clue":"first","response":"What is x?","keywords":["A"]},
                 {"id":1,"clue":"second","response":"What is x?","keywords":["A"]}]""");
        List<EnrichedClue> parsed = EnrichmentPrompt.parse(reply, batch);
        assertEquals(1, parsed.size());
        assertEquals("first", parsed.get(0).clue());
        assertFalse(parsed.get(0).keywords().isEmpty());
    }
}
