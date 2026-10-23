package party.jeoparody.enrich;

import java.util.List;

/** A keyword that needs a board title, with a few sample responses to inspire it. */
public record KeywordToTitle(long id, String keyword, List<String> sampleResponses) {
}
