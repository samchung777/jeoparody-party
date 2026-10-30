package party.jeoparody.genre;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sorts keywords into general categories. Gemini in the app, faked in tests. */
public interface GenreClassifier {

    Map<Long, Set<String>> classify(List<KeywordToClassify> keywords) throws IOException, InterruptedException;
}
