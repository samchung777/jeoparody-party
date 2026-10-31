package party.jeoparody.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Checks the review prompt and parsing of Gemini's verdicts.
 */
class ReviewPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClueToReview> batch = List.of(
            new ClueToReview(1, "Film", "This man directed Jaws", "Who is Steven Spielberg?"),
            new ClueToReview(2, "Film", "This shark hunter captains the Orca", "Who is Quint?"),
            new ClueToReview(3, "Music", "This band sang Bohemian Rhapsody", "What is Queen?"));

    @Test
    void promptListsItemsAndKeepsPerformersAndLiveActionCast() {
        String prompt = ReviewPrompt.build(mapper, batch);
        assertTrue(prompt.contains("This shark hunter captains the Orca"));
        assertTrue(prompt.contains("The performing artist of a song is NOT behind the scenes."));
        assertTrue(prompt.contains("In live-action movies, TV and stage shows, the actor who plays a character is fine"));
    }

    @Test
    void promptRemovesVoiceActorsEverywhereAndActorsInAnimeWithGenresAsContext() {
        String prompt = ReviewPrompt.build(mapper, List.of(new ClueToReview(7, "Naruto",
                "This actress voices Naruto in the English dub", "Who is Maile Flanagan?", "Anime & Manga|TV")));
        assertTrue(prompt.contains("ANY clue about a voice actor or voice actress"));
        assertTrue(prompt.contains("in anime, manga, cartoons, animated films or series, and video games"));
        assertTrue(prompt.contains("\"genres\" : \"Anime & Manga, TV\""));
        assertTrue(ReviewPrompt.VERSION >= 2, "bumped so every clue is reviewed again under the actor rules");
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
