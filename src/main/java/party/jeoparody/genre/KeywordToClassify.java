package party.jeoparody.genre;

import java.util.List;

/** A keyword without a genre yet, with a few of its responses so Gemini can tell what it is about. */
public record KeywordToClassify(long id, String keyword, List<String> sampleResponses) {
}
