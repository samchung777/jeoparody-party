package party.jeoparody.enrich;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Generates board titles for keywords. Implemented by Gemini; faked in tests. */
public interface CategoryTitler {

    Map<Long, String> title(List<KeywordToTitle> keywords) throws IOException, InterruptedException;
}
