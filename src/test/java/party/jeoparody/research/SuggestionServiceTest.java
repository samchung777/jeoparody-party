package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** Unit test (Surefire, `*Test`): cleaning and rate-limiting topic suggestions typed on phones. */
class SuggestionServiceTest {

    @Test
    void topicsAreCleaned() {
        assertEquals("Gravity Falls", SuggestionService.clean("  Gravity   Falls "));
        assertEquals("Avatar The Last Airbender Anime", SuggestionService.clean("Avatar = The Last Airbender [Anime]"),
                "topic-syntax characters can't sneak in");
        assertEquals("Spy x Family!", SuggestionService.clean("Spy x Family!"));
    }

    @Test
    void unusableTopicsAreRefusedWithAMessage() {
        assertThrows(IllegalArgumentException.class, () -> SuggestionService.clean(null));
        assertThrows(IllegalArgumentException.class, () -> SuggestionService.clean(" # "));
        assertThrows(IllegalArgumentException.class, () -> SuggestionService.clean("x".repeat(81)));
        IllegalArgumentException link = assertThrows(IllegalArgumentException.class,
                () -> SuggestionService.clean("see https://example.com"));
        assertEquals("Suggest a topic, not a link", link.getMessage());
    }

    @Test
    void eachTeamGetsThreeSuggestionsPerGame() {
        ResearchRepository repo = mock(ResearchRepository.class);
        SuggestionService service = new SuggestionService(repo);
        for (int i = 0; i < 3; i++) {
            service.submit("Topic " + i, "ABCD", 1, "team-1", "Red");
        }
        assertThrows(IllegalArgumentException.class, () -> service.submit("Topic 4", "ABCD", 1, "team-1", "Red"));
        service.submit("Topic 4", "ABCD", 1, "team-2", "Blue"); // another team
        service.submit("Topic 5", "ABCD", 2, "team-1", "Red"); // the next game in the room
        verify(repo, times(5)).suggest(any(), eq("ABCD"), any());
    }
}
