package party.jeoparody.wiki;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches articles through the MediaWiki Action API (the official API, not HTML scraping).
 * Tries the exact title first, then falls back to search, skipping disambiguation pages.
 */
@Component
public class MediaWikiSource implements WikiSource {

    /** Wikimedia asks API clients to identify themselves with a contact URL. */
    static final String USER_AGENT = "JeoparodyParty/1.0 (https://github.com/samchung777/jeoparody-party)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final WikiProperties props;

    public MediaWikiSource(ObjectMapper mapper, WikiProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public Optional<WikiPage> fetch(String pageQuery) throws IOException, InterruptedException {
        Optional<WikiPage> main = article(pageQuery);
        if (main.isEmpty()) {
            for (String title : search(pageQuery)) {
                if (!WikiText.titleMatchesQuery(title, pageQuery)) {
                    continue; // a search hit that isn't what was asked for
                }
                main = article(title);
                if (main.isPresent()) {
                    break;
                }
            }
        }
        if (main.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withCharacters(main.get()));
    }

    /**
     * Many franchises have a "List of X characters" article: the best possible material for clues about
     * characters. If it exists, append it (the main article keeps ~60% of the length budget).
     */
    private WikiPage withCharacters(WikiPage page) throws IOException, InterruptedException {
        String base = page.title().replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        Optional<WikiPage> characters = article("List of " + base + " characters");
        if (characters.isEmpty()) {
            return page;
        }
        return combine(page, characters.get(), props.maxArticleChars());
    }

    /** Main article first, then the character list, within maxChars. Package-private for tests. */
    static WikiPage combine(WikiPage main, WikiPage characters, int maxChars) {
        String mainText = WikiText.truncateAtSentence(main.text(), maxChars * 6 / 10);
        String header = "\n\nCharacters (from \"" + characters.title() + "\"):\n";
        int remaining = Math.max(0, maxChars - mainText.length() - header.length());
        String text = remaining < 500 ? mainText : mainText + header + WikiText.truncateAtSentence(characters.text(), remaining);
        return new WikiPage(main.pageId(), main.title(), main.url(), text);
    }

    /** The article with this exact title (following redirects), unless missing or a disambiguation page. */
    private Optional<WikiPage> article(String title) throws IOException, InterruptedException {
        JsonNode root = get("action=query&format=json&formatversion=2&redirects=1"
                + "&prop=extracts%7Cpageprops%7Cinfo&inprop=url&ppprop=disambiguation"
                + "&explaintext=1&exsectionformat=wiki&titles=" + encode(title));
        return parsePage(root, props.maxArticleChars());
    }

    private List<String> search(String query) throws IOException, InterruptedException {
        JsonNode root = get("action=query&format=json&formatversion=2&list=search&srlimit=5&srsearch=" + encode(query));
        List<String> titles = new ArrayList<>();
        root.path("query").path("search").forEach(n -> titles.add(n.path("title").asText()));
        return titles;
    }

    /** Parses a prop=extracts|pageprops|info response. Package-private for tests. */
    static Optional<WikiPage> parsePage(JsonNode root, int maxChars) {
        JsonNode page = root.path("query").path("pages").path(0);
        if (page.isMissingNode() || page.path("missing").asBoolean(false) || page.path("invalid").asBoolean(false)
                || page.path("pageprops").has("disambiguation")) {
            return Optional.empty();
        }
        String text = WikiText.cleanArticle(page.path("extract").asText(""), maxChars, true);
        if (text.length() < 500) {
            return Optional.empty(); // stubs don't have enough facts for a category
        }
        String title = page.path("title").asText();
        String url = page.path("fullurl").asText("https://en.wikipedia.org/wiki/" + title.replace(' ', '_'));
        return Optional.of(new WikiPage(page.path("pageid").asLong(), title, url, text));
    }

    private JsonNode get(String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.apiUrl() + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Wikipedia API returned HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
