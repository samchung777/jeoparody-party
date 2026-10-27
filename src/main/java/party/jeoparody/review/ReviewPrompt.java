package party.jeoparody.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Prompt and parser for the behind-the-scenes review. No I/O. */
public final class ReviewPrompt {

    /** Bump when the review rules change: every live clue is reviewed again. */
    public static final int VERSION = 1;
    static final int MAX_REASON = 300;

    /**
     * Static helpers only; VERSION above is stored in clue.review_version so a better prompt re-reviews old clues.
     */
    private ReviewPrompt() {
    }

    /**
     * Asks Gemini to flag clues about production rather than story (directors, release dates, box office, studios).
     */
    public static String build(ObjectMapper mapper, List<ClueToReview> batch) {
        ArrayNode items = mapper.createArrayNode();
        for (ClueToReview c : batch) {
            ObjectNode n = items.addObject();
            n.put("id", c.id());
            n.put("category", c.category());
            n.put("clue", c.clue());
            n.put("response", c.response());
        }
        return """
                You review clues for a pop-culture trivia party game. Players want clues about the stories and
                things fans love: plot events, characters, the main cast (actors and voice actors and who they
                play), performing artists and bands, songs, in-universe places, items and lore, and general
                knowledge (science, history, geography, scripture stories...).

                Mark a clue "offTopic": true ONLY if it is mainly about how or by whom something was made or sold:
                - who directed, wrote, authored, produced, composed, designed, developed or published it,
                  or which studio, network or record label made it
                - development, production, filming, recording, casting decisions or budgets
                - release dates or years, consoles/platforms it came out on, sales, box office, ratings,
                  reviews, awards or nominations
                - the real-world year or city a story is set in or was made in
                The performing artist of a song or the actor who plays a character is NOT behind the scenes.
                When unsure, keep the clue ("offTopic": false).

                For every item return {"id", "offTopic", "reason"} where reason is a few words when offTopic.

                Items:
                %s
                """.formatted(items.toPrettyString());
    }

    /** Structured-output schema: for each clue id, offTopic (true/false) and a short reason. */
    public static ObjectNode responseSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "ARRAY");
        ObjectNode item = schema.putObject("items").put("type", "OBJECT");
        ObjectNode p = item.putObject("properties");
        p.putObject("id").put("type", "INTEGER");
        p.putObject("offTopic").put("type", "BOOLEAN");
        p.putObject("reason").put("type", "STRING");
        item.putArray("required").add("id").add("offTopic");
        return schema;
    }

    /** Clue id -> reason, for items in the batch marked offTopic. */
    public static Map<Long, String> parse(JsonNode array, List<ClueToReview> batch) {
        Set<Long> ids = batch.stream().map(ClueToReview::id).collect(Collectors.toSet());
        Map<Long, String> out = new LinkedHashMap<>();
        for (JsonNode n : array) {
            long id = n.path("id").asLong(-1);
            if (!ids.contains(id) || !n.path("offTopic").asBoolean(false)) {
                continue;
            }
            String reason = n.path("reason").asText("").strip();
            if (reason.isEmpty()) {
                reason = "behind-the-scenes";
            }
            out.put(id, reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON));
        }
        return out;
    }
}
