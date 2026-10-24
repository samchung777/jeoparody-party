package party.jeoparody.wiki;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Writes verified clues about a topic from its article. Gemini in the app, faked in tests. */
public interface WikiClueGenerator {

    /**
     * @param excludedAnswers answer keys ({@link WikiText#answerKey}) already used for this page, to avoid repeats
     */
    List<GeneratedClue> generate(WikiText.TopicSpec topic, WikiPage page, int count, Set<String> excludedAnswers)
            throws IOException, InterruptedException;
}
