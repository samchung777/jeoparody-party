package party.jeoparody.research;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A fan wiki page link pasted by the host ("https://naruto.fandom.com/wiki/Itachi_Uchiha"), turned into the page
 * title and the likely places of the wiki's api.php. Pure parsing, unit-tested by FanWikiPageLinkTest.
 *
 * MediaWiki sites put api.php in different places (Fandom: /api.php, Wikipedia-style: /w/api.php, some:
 * /mediawiki/api.php, language wikis: /fr/api.php), so FanWikiClient tries the candidates in order.
 */
public record FanWikiPageLink(URI link, String title, List<URI> apiCandidates) {

    /** Namespaces that aren't articles. */
    private static final Set<String> NOT_ARTICLES = Set.of("special", "file", "image", "category", "template",
            "user", "user talk", "talk", "mediawiki", "help", "module");

    /** Parses the link; throws IllegalArgumentException with a message for the host if it isn't a wiki page. */
    public static FanWikiPageLink parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Paste a link to a fan wiki page");
        }
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That doesn't look like a link");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("The link must start with https://");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String title = null;
        String prefix = "";
        int wiki = path.indexOf("/wiki/");
        if (wiki >= 0) {
            title = decode(path.substring(wiki + "/wiki/".length()));
            prefix = path.substring(0, wiki);
        } else if (path.endsWith("/index.php") && uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.startsWith("title=")) {
                    title = decode(pair.substring("title=".length()));
                }
            }
            prefix = path.substring(0, path.length() - "/index.php".length());
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Paste the link of one wiki article (it usually contains /wiki/)");
        }
        title = title.replace('_', ' ').strip();
        int colon = title.indexOf(':');
        if (colon > 0 && NOT_ARTICLES.contains(title.substring(0, colon).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("That link is a " + title.substring(0, colon) + " page, not an article");
        }
        String base = "https://" + uri.getRawAuthority();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(base + prefix + "/api.php");
        candidates.add(base + prefix + "/w/api.php");
        candidates.add(base + "/w/api.php");
        candidates.add(base + "/api.php");
        candidates.add(base + "/mediawiki/api.php");
        return new FanWikiPageLink(uri, title, candidates.stream().map(URI::create).toList());
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
