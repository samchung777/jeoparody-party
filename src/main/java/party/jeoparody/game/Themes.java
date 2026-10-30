package party.jeoparody.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * General categories ("genres") that every keyword falls into, and the themes a host can pick to narrow a game.
 *
 * Where it fits: each keyword gets one or more genres (keyword_genre table, filled by GenreService from Open
 * Trivia DB categories, keyword names and Gemini). The host picks a theme in the lobby, and Game only offers
 * keywords whose genres overlap the theme. Players browse the vote by genre.
 * Why it matters: it turns one big random pool into a game with a vibe ("Anime Night", "Movie Night").
 */
public final class Themes {

    /** The general categories. Fixed so Gemini can only answer with one of them (structured output enum). */
    public static final List<String> GENRES = List.of(
            "Movies", "TV", "Cartoons", "Anime & Manga", "Video Games", "Books", "Comics", "Music",
            "Musicals & Theatre", "Celebrities", "Board Games", "Bible & Mythology", "Food");

    /** A named theme: keywords match when they share at least one genre. No genres means everything. */
    public record Theme(String name, String description, Set<String> genres) {

        /** True when a keyword with these genres belongs in a game with this theme. */
        public boolean matches(Set<String> keywordGenres) {
            if (genres.isEmpty()) {
                return true;
            }
            for (String g : keywordGenres) {
                if (genres.contains(g)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final Theme EVERYTHING = new Theme("Everything", "Every category we have", Set.of());

    private static final Map<String, Theme> THEMES = new LinkedHashMap<>();

    static {
        add(EVERYTHING);
        add(new Theme("Movie Night", "Films only", Set.of("Movies")));
        add(new Theme("Binge Watch", "TV shows", Set.of("TV")));
        add(new Theme("Screen Time", "Movies, TV and cartoons", Set.of("Movies", "TV", "Cartoons")));
        add(new Theme("Saturday Morning", "Cartoons and anime", Set.of("Cartoons", "Anime & Manga")));
        add(new Theme("Anime Night", "Anime and manga", Set.of("Anime & Manga")));
        add(new Theme("Game Night", "Video games and board games", Set.of("Video Games", "Board Games")));
        add(new Theme("Nerd Night", "Games, anime and comics",
                Set.of("Video Games", "Anime & Manga", "Comics", "Board Games")));
        add(new Theme("Bookworms", "Books and comics", Set.of("Books", "Comics")));
        add(new Theme("Sing-Along", "Music and musicals", Set.of("Music", "Musicals & Theatre")));
        add(new Theme("Red Carpet", "Celebrities, movies and TV", Set.of("Celebrities", "Movies", "TV")));
        add(new Theme("Sunday School", "The Bible and mythology", Set.of("Bible & Mythology")));
        add(new Theme("Kitchen Party", "Food and cooking shows", Set.of("Food")));
    }

    private Themes() {
    }

    private static void add(Theme t) {
        THEMES.put(t.name().toLowerCase(), t);
    }

    /** Every theme, in menu order (Everything first). */
    public static List<Theme> all() {
        return List.copyOf(THEMES.values());
    }

    /** Looks a theme up by name, ignoring case. */
    public static Optional<Theme> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(THEMES.get(name.strip().toLowerCase()));
    }
}
