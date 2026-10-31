package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): which keywords may be shown with a clue without giving the answer away.
 */
class ClueTagsTest {

    @Test
    void dropsTheCategoryAndAnyKeywordThatContainsOrSharesTheAnswer() {
        List<String> tags = ClueTags.safe(
                List.of("Star Wars", "Luke Skywalker", "Skywalker Family", "Tatooine", "Jedi", "Luke"),
                "Who is Luke Skywalker?", "Star Wars");
        assertEquals(List.of("Tatooine", "Jedi"), tags);
    }

    @Test
    void keywordInsideTheAnswerOrAnswerInsideTheKeywordIsHidden() {
        assertEquals(List.of("Broadway Musicals"),
                ClueTags.safe(List.of("Burr", "Broadway Musicals"), "Who is Aaron Burr?", "Hamilton"));
        assertEquals(List.of("Season 2"),
                ClueTags.safe(List.of("Zoe Washburne", "Season 2"), "Who is Zoe?", "Firefly"));
    }

    @Test
    void commonWordsAndPartialWordsDontCountAsSpoilers() {
        assertEquals(List.of("The Heart of the Cards", "Duel Monsters"),
                ClueTags.safe(List.of("The Heart of the Cards", "Duel Monsters"), "What is the art of war?", "Yu-Gi-Oh"));
    }

    @Test
    void caseAccentsAndDuplicatesAreIgnoredAndTheListIsCapped() {
        assertEquals(List.of("Pokémon Red", "Kanto", "Gym Leaders", "Team Rocket"),
                ClueTags.safe(List.of("Pokémon Red", "pokemon red", "Kanto", "Gym Leaders", "Team Rocket", "Badges"),
                        "What is Pallet Town?", "Pokemon"));
        assertEquals(List.of(), ClueTags.safe(List.of("POKEMON"), "What is Kanto?", "Pokémon"));
    }
}
