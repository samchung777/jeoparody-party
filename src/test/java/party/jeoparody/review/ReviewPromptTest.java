package party.jeoparody.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ReviewPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClueToReview> batch = List.of(
            new ClueToReview(1, "Film", "This man directed Jaws", "Who is Steven Spielberg?"),
            new ClueToReview(2, "Film", "This shark hunter captains the Orca", "Who is Quint?"),
            new ClueToReview(3, "Music", "This band sang Bohemian Rhapsody", "What is Queen?"));

    @Test
    void promptListsItemsAndKeepsPerformersAndCast() {
        String prompt = ReviewPrompt.build(mapper, batch);
        assertTrue(prompt.contains("This shark hunter captains the Orca"));
        assertTrue(prompt.contains("The performing artist of a song or the actor who plays a character is NOT behind the scenes."));
    }

    @Test
    void onlyOffTopicItemsFromTheBatchAreReturned() throws Exception {
        var reply = mapper.readTree("""
                [{"id":1,"offTopic":true,"reason":"asks for the director"},
                 {"id":2,"offTopic":false},
                 {"id":3,"offTopic":"no"},
                 {"id":99,"offTopic":true,"reason":"not in this batch"},
                 {"id":3,"offTopic":true}]""");
        Map<Long, String> out = ReviewPrompt.parse(reply, batch);
        assertEquals(Map.of(1L, "asks for the director", 3L, "behind-the-scenes"), out);
    }
}
