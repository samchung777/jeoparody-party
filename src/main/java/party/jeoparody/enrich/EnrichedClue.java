package party.jeoparody.enrich;

import java.util.List;

/** Jeopardy-style wording and subject keywords for one clue. */
public record EnrichedClue(long id, String clue, String response, List<String> keywords) {
}
