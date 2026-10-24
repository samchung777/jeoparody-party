package party.jeoparody.wiki;

import java.io.IOException;
import java.util.List;

/** Writes verified clues about a topic from its article. Gemini in the app, faked in tests. */
public interface WikiClueGenerator {

    List<GeneratedClue> generate(String topicKeyword, WikiPage page, int count) throws IOException, InterruptedException;
}
