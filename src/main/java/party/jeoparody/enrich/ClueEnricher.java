package party.jeoparody.enrich;

import java.io.IOException;
import java.util.List;

/** Turns raw trivia into Jeopardy-format clues with keywords. Implemented by Gemini; faked in tests. */
public interface ClueEnricher {

    List<EnrichedClue> enrich(List<ClueToEnrich> batch, List<String> existingKeywords)
            throws IOException, InterruptedException;
}
