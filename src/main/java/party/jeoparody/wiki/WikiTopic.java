package party.jeoparody.wiki;

import java.time.Instant;

/** A queued or processed topic, for the trivia library's status table. */
public record WikiTopic(long id, String topic, String status, String pageTitle, String pageUrl, int cluesGenerated,
        int cluesAdded, String error, Instant updatedAt) {
}
