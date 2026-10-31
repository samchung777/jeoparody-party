package party.jeoparody.genre;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic genre rules, tried before asking Gemini: the keyword's own name ("Shounen Anime" is Anime &amp;
 * Manga) and, failing that, where its clues came from (a keyword whose clues are mostly Open Trivia DB "Film"
 * questions is Movies). Pure functions, unit-tested by GenreRulesTest.
 */
public final class GenreRules {

    /** Open Trivia DB category display names (OpenTdbParser.displayName) -> genre. */
    static final Map<String, String> OPEN_TDB = Map.ofEntries(
            Map.entry("Film", "Movies"),
            Map.entry("Television", "TV"),
            Map.entry("Cartoon & Animations", "Cartoons"),
            Map.entry("Japanese Anime & Manga", "Anime & Manga"),
            Map.entry("Video Games", "Video Games"),
            Map.entry("Books", "Books"),
            Map.entry("Comics", "Comics"),
            Map.entry("Music", "Music"),
            Map.entry("Musicals & Theatres", "Musicals & Theatre"),
            Map.entry("Celebrities", "Celebrities"),
            Map.entry("Board Games", "Board Games"),
            Map.entry("Mythology", "Bible & Mythology"));

    /** Words in a keyword's name that settle its genre on their own. Checked in order; all matches count. */
    private static final List<Map.Entry<Pattern, String>> NAME_RULES = List.of(
            Map.entry(word("anime|manga|shou?nen|shoujo|seinen"), "Anime & Manga"),
            Map.entry(word("video games?|nintendo|playstation|xbox|pok[eé]mon games?"), "Video Games"),
            Map.entry(word("cartoons?|animated series|cartoon network|nickelodeon|nicktoons"), "Cartoons"),
            Map.entry(word("musicals?|broadway|theatre|theater"), "Musicals & Theatre"),
            Map.entry(word("bible|old testament|new testament|gospels?|mythology|myths?|scripture|prophets|apostles"
                    + "|disciples|parables|patriarchs|levitical law|eschatology|jesus'?|israel's exodus|the judges"
                    + "|the kings|psalms|epistles"), "Bible & Mythology"),
            Map.entry(word("food|cooking|chefs?|baking|food network"), "Food"),
            Map.entry(word("movies?|films?|cinema|blockbusters?"), "Movies"),
            Map.entry(word("tv|television|sitcoms?|reality tv"), "TV"),
            Map.entry(word("novels?|books?|authors?|literature"), "Books"),
            Map.entry(word("comics?|superheroes?|marvel|dc comics"), "Comics"),
            Map.entry(word("songs?|music|bands?|albums?|singers?|rappers?|hip hop|pop stars?"), "Music"),
            Map.entry(word("board games?"), "Board Games"),
            Map.entry(word("celebrit(y|ies)|hollywood stars?"), "Celebrities"));

    /** Share of a keyword's clues that must come from one Open Trivia DB genre to decide it. */
    static final double MAJORITY = 0.6;

    private GenreRules() {
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)(^|[^\\p{L}\\p{N}])(" + alternatives + ")([^\\p{L}\\p{N}]|$)");
    }

    /** Genres the keyword's name alone implies ("90s Cartoons" -> Cartoons). Empty if none. */
    public static Set<String> fromName(String keyword) {
        Set<String> out = new LinkedHashSet<>();
        if (keyword == null) {
            return out;
        }
        for (Map.Entry<Pattern, String> rule : NAME_RULES) {
            if (rule.getKey().matcher(keyword).find()) {
                out.add(rule.getValue());
            }
        }
        return out;
    }

    /**
     * Genres for a keyword: name rules first; otherwise the Open Trivia DB genre that holds at least 60% of its
     * clues. {@code clueCountsByCategory} maps category display name -> number of the keyword's clues in it.
     * Empty means "ask Gemini".
     */
    public static Set<String> pick(String keyword, Map<String, Integer> clueCountsByCategory) {
        Set<String> byName = fromName(keyword);
        if (!byName.isEmpty()) {
            return byName;
        }
        int total = clueCountsByCategory.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return Set.of();
        }
        Map<String, Integer> byGenre = new java.util.HashMap<>();
        clueCountsByCategory.forEach((category, n) -> {
            String genre = OPEN_TDB.get(category);
            if (genre != null) {
                byGenre.merge(genre, n, Integer::sum);
            }
        });
        return byGenre.entrySet().stream()
                .filter(e -> e.getValue() >= MAJORITY * total)
                .map(Map.Entry::getKey)
                .findFirst()
                .map(Set::of)
                .orElse(Set.of());
    }
}
