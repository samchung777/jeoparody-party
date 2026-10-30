package party.jeoparody.research;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import party.jeoparody.wiki.WikiText;

/**
 * The fan-wiki allow-list. Only wikis listed in trivia.research.fan-wikis are ever contacted, each for the
 * keywords named on its line, and never a blocked host (Fandom and its old Wikia domains, whose terms of use
 * forbid scraping and feeding content to AI). Pure parsing, unit-tested by FanWikisTest.
 */
public final class FanWikis {

    private final Map<String, URI> byKeyword = new LinkedHashMap<>();
    private final List<String> rejected = new java.util.ArrayList<>();

    public FanWikis(List<String> entries, List<String> blockedHosts) {
        for (String entry : entries == null ? List.<String>of() : entries) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) {
                rejected.add(entry + " (expected 'Keyword = https://host/w/api.php')");
                continue;
            }
            URI api;
            try {
                api = URI.create(entry.substring(eq + 1).strip());
            } catch (IllegalArgumentException e) {
                rejected.add(entry + " (bad URL)");
                continue;
            }
            String why = problem(api, blockedHosts);
            if (why != null) {
                rejected.add(entry + " (" + why + ")");
                continue;
            }
            for (String keyword : entry.substring(0, eq).split(",")) {
                String k = key(keyword);
                if (!k.isEmpty()) {
                    byKeyword.put(k, api);
                }
            }
        }
    }

    /** Why this API URL can't be used, or null if it's fine. */
    static String problem(URI api, List<String> blockedHosts) {
        if (!"https".equalsIgnoreCase(api.getScheme()) || api.getHost() == null) {
            return "must be an https URL";
        }
        if (api.getPath() == null || !api.getPath().endsWith("api.php")) {
            return "must point at the wiki's api.php";
        }
        String host = api.getHost().toLowerCase(Locale.ROOT);
        for (String blocked : blockedHosts == null ? List.<String>of() : blockedHosts) {
            String b = blocked.strip().toLowerCase(Locale.ROOT);
            if (!b.isEmpty() && (host.equals(b) || host.endsWith("." + b))) {
                return "blocked host: " + b;
            }
        }
        return null;
    }

    private static String key(String keyword) {
        return WikiText.keyword(keyword).toLowerCase(Locale.ROOT);
    }

    /** The allow-listed fan wiki's api.php for this keyword, if there is one. */
    public Optional<URI> forKeyword(String keyword) {
        return keyword == null ? Optional.empty() : Optional.ofNullable(byKeyword.get(key(keyword)));
    }

    /** Lines that were ignored, with the reason (logged at start-up, shown on the library page). */
    public List<String> rejected() {
        return List.copyOf(rejected);
    }

    public Map<String, URI> entries() {
        return Map.copyOf(byKeyword);
    }
}
