package party.jeoparody.game;

/** A keyword players can vote for. {@code title} is the punny board title (falls back to the keyword). */
public record KeywordOption(long id, String name, String title, int clueCount) {

    /** The punny title Gemini wrote (V3 migration), or the plain keyword if there isn't one yet. */
    public String displayTitle() {
        return title == null || title.isBlank() ? name : title;
    }
}
