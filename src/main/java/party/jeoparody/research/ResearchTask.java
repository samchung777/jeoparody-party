package party.jeoparody.research;

import java.time.Instant;

/** One unit of research work (research_task row). {@code keywordId} is set for stale categories and fan-wiki passes. */
public record ResearchTask(long id, String kind, Long keywordId, String topic, String status, String sources,
        String sourceUrl, int cluesAdded, String error, String gameCode, Instant updatedAt) {

    public static final String STALE = "stale_category";
    public static final String SUGGESTION = "suggestion";
    public static final String MANUAL = "manual";
    /** First pass over a category's allow-listed fan wiki (fan wiki only, like a stale category). */
    public static final String FAN_WIKI = "fan_wiki";
}
