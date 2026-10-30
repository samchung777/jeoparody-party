package party.jeoparody.game;

import java.util.Set;

/**
 * A keyword players can vote for. {@code title} is the punny board title (falls back to the keyword).
 * {@code genres} are its general categories (Movies, TV, ...), used by themes and genre browsing.
 */
public record KeywordOption(long id, String name, String title, int clueCount, Set<String> genres) {

    public KeywordOption {
        genres = genres == null ? Set.of() : Set.copyOf(genres);
    }

    /** A keyword with no genres yet (not classified): only shown under the "Everything" theme. */
    public KeywordOption(long id, String name, String title, int clueCount) {
        this(id, name, title, clueCount, Set.of());
    }

    /** The punny title Gemini wrote (V3 migration), or the plain keyword if there isn't one yet. */
    public String displayTitle() {
        return title == null || title.isBlank() ? name : title;
    }
}
