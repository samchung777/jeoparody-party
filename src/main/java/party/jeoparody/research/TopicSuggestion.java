package party.jeoparody.research;

import java.time.Instant;

/** A topic a player or host suggested during a game (topic_suggestion row). */
public record TopicSuggestion(long id, String topic, String gameCode, String suggestedBy, String status,
        Long researchTaskId, Instant createdAt) {
}
