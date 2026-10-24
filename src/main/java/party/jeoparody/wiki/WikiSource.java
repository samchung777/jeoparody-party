package party.jeoparody.wiki;

import java.io.IOException;
import java.util.Optional;

/** Finds and fetches the article for a topic. MediaWiki in the app, faked in tests. */
public interface WikiSource {

    Optional<WikiPage> fetch(String pageQuery) throws IOException, InterruptedException;
}
