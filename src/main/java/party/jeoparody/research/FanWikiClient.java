package party.jeoparody.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import party.jeoparody.wiki.MediaWikiSource;
import party.jeoparody.wiki.WikiPage;
import party.jeoparody.wiki.WikiText;

/**
 * Reads an allow-listed fan wiki through its MediaWiki API (the official API, no HTML scraping): searches the
 * wiki for the keyword, fetches the top few pages' wikitext in one request, cleans it with FanWikiText and keeps
 * the story sections. The result is one combined page for the clue writer.
 */
@Component
public class FanWikiClient {

    private static final Logger log = LoggerFactory.getLogger(FanWikiClient.class);
    static final int MAX_PAGES = 6;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final FanWikis wikis;
    private final int maxChars;
    private final List<String> blockedHosts;

    public FanWikiClient(ObjectMapper mapper, ResearchProperties props) {
        this.mapper = mapper;
        this.blockedHosts = props.blockedHosts();
        this.wikis = new FanWikis(props.fanWikis(), props.blockedHosts());
        this.maxChars = props.maxArticleChars();
        wikis.rejected().forEach(r -> log.warn("Ignoring fan wiki entry: {}", r));
    }

    public FanWikis wikis() {
        return wikis;
    }

    /** The keyword's allow-listed fan wiki, read and combined; empty if none is listed or nothing usable is found. */
    public Optional<WikiPage> fetch(String keyword) throws IOException, InterruptedException {
        Optional<URI> api = wikis.forKeyword(keyword);
        if (api.isEmpty()) {
            return Optional.empty();
        }
        List<String> titles = searchTitles(get(api.get(), "action=query&format=json&formatversion=2&list=search"
                + "&srlimit=" + MAX_PAGES + "&srsearch=" + encode(keyword)));
        if (titles.isEmpty()) {
            return Optional.empty();
        }
        JsonNode pages = get(api.get(), "action=query&format=json&formatversion=2&redirects=1&prop=info%7Crevisions"
                + "&inprop=url&rvprop=content&rvslots=main&titles=" + encode(String.join("|", titles)));
        return combine(parsePages(pages, titles), maxChars);
    }

    /**
     * One page the host pasted, from any MediaWiki fan wiki (not only allow-listed ones, but never a blocked host).
     * Tries the allow-listed api.php for that host first, then the usual api.php locations; story sections only.
     * Empty if the wiki has no such article or it is too short to write clues from.
     */
    public Optional<WikiPage> fetchPage(FanWikiPageLink link) throws IOException, InterruptedException {
        List<URI> apis = new ArrayList<>();
        wikis.entries().values().stream().filter(a -> a.getHost().equalsIgnoreCase(link.link().getHost()))
                .findFirst().ifPresent(apis::add);
        link.apiCandidates().stream().filter(a -> !apis.contains(a)).forEach(apis::add);
        String blocked = FanWikis.problem(apis.get(0), blockedHosts);
        if (blocked != null) {
            throw new IOException("Can't use " + link.link().getHost() + ": " + blocked);
        }
        IOException last = null;
        for (URI api : apis) {
            JsonNode root;
            try {
                root = get(api, "action=query&format=json&formatversion=2&redirects=1&prop=info%7Crevisions"
                        + "&inprop=url&rvprop=content&rvslots=main&titles=" + encode(link.title()));
            } catch (IOException e) { // not the API here (404, an HTML page...): try the next location
                last = e;
                continue;
            }
            if (!root.has("query")) {
                continue;
            }
            return singlePage(root, maxChars);
        }
        throw last != null ? last : new IOException("No MediaWiki API found on " + link.link().getHost());
    }

    /** The one article in a prop=revisions reply, cleaned to its story sections. Package-private for tests. */
    static Optional<WikiPage> singlePage(JsonNode root, int maxChars) {
        List<FanPage> pages = parsePages(root, List.of());
        if (pages.isEmpty()) {
            return Optional.empty();
        }
        FanPage p = pages.get(0);
        String body = WikiText.cleanArticle(p.text(), maxChars, true);
        if (body.length() < 500) {
            return Optional.empty();
        }
        long id = Math.abs((p.url() + p.title()).hashCode());
        return Optional.of(new WikiPage(id, p.title() + " (fan wiki)", p.url(), body));
    }

    /** Titles from a list=search reply, in rank order. Package-private for tests. */
    static List<String> searchTitles(JsonNode root) {
        List<String> titles = new ArrayList<>();
        for (JsonNode hit : root.path("query").path("search")) {
            String t = hit.path("title").asText("");
            if (!t.isBlank()) {
                titles.add(t);
            }
        }
        return titles;
    }

    /** One page fetched from a fan wiki: id, title, URL and cleaned text. */
    record FanPage(long id, String title, String url, String text) {
    }

    /** Pages from a prop=info|revisions reply, in the search order, cleaned; missing and empty ones skipped. */
    static List<FanPage> parsePages(JsonNode root, List<String> order) {
        List<FanPage> pages = new ArrayList<>();
        for (JsonNode p : root.path("query").path("pages")) {
            if (p.path("missing").asBoolean(false) || p.path("invalid").asBoolean(false)) {
                continue;
            }
            String wikitext = p.path("revisions").path(0).path("slots").path("main").path("content").asText("");
            String text = FanWikiText.clean(wikitext);
            if (text.length() < 200) {
                continue; // stubs and redirect leftovers
            }
            pages.add(new FanPage(p.path("pageid").asLong(), p.path("title").asText(), p.path("fullurl").asText(""), text));
        }
        pages.sort(java.util.Comparator.comparingInt(fp -> {
            int i = order.indexOf(fp.title());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        return pages;
    }

    /**
     * Joins pages into one article for the clue writer, best-ranked first, each cut to an equal share of the
     * budget at a sentence end and limited to story sections. The first page's URL is the source.
     */
    static Optional<WikiPage> combine(List<FanPage> pages, int maxChars) {
        if (pages.isEmpty()) {
            return Optional.empty();
        }
        int share = Math.max(1500, maxChars / pages.size());
        StringBuilder text = new StringBuilder();
        for (FanPage p : pages) {
            String body = WikiText.cleanArticle(p.text(), share, true);
            if (body.isBlank() || text.length() + body.length() > maxChars) {
                continue;
            }
            text.append("\n\n== ").append(p.title()).append(" ==\n").append(body);
        }
        if (text.length() < 500) {
            return Optional.empty();
        }
        FanPage first = pages.get(0);
        long id = Math.abs((first.url() + first.title()).hashCode());
        return Optional.of(new WikiPage(id, first.title() + " (fan wiki)", first.url(), text.toString().strip()));
    }

    private JsonNode get(URI api, String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", MediaWikiSource.USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Fan wiki API returned HTTP " + response.statusCode() + " (" + api.getHost() + ")");
        }
        return mapper.readTree(response.body());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
