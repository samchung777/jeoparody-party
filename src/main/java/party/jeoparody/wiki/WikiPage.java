package party.jeoparody.wiki;

/** A Wikipedia article, already cleaned for prompting. */
public record WikiPage(long pageId, String title, String url, String text) {
}
