package party.jeoparody.enrich;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Prompt and parser for giving keywords witty Jeopardy!-style category titles. No I/O. */
public final class TitlePrompt {

    static final int MAX_TITLE_LENGTH = 40;
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Static helpers only: no instances. */
    private TitlePrompt() {
    }

    /**
     * Asks Gemini for a witty title per keyword, with sample responses so the pun fits the actual clues.
     */
    public static String build(ObjectMapper mapper, List<KeywordToTitle> keywords) {
        ArrayNode items = mapper.createArrayNode();
        for (KeywordToTitle k : keywords) {
            ObjectNode n = items.addObject();
            n.put("id", k.id());
            n.put("keyword", k.keyword());
            ArrayNode samples = n.putArray("sampleResponses");
            k.sampleResponses().forEach(samples::add);
        }
        return """
                You write category titles for the TV quiz show Jeopardy!.
                For each keyword below, write one category title for a pop-culture trivia board.

                Rules:
                - 2 to 5 words, at most 40 characters.
                - Thematic and fun: puns, wordplay, rhymes, song or movie title riffs, alliteration.
                  Examples: "80s Movies" -> "Totally Tubular Cinema", "Broadway Musicals" -> "Show Tune Sing-Along",
                  "Star Wars" -> "May The Quiz Be With You", "90s Music" -> "Mixtape Memories",
                  "Video Game Consoles" -> "Press Start".
                - A player must still be able to tell what the category covers.
                - Family friendly. Do not reveal any of the sample responses.
                - Every title must be different.

                Keywords (with sample responses for context):
                %s

                Reply with a JSON array of {"id", "title"} objects, one per keyword.
                """.formatted(items.toPrettyString());
    }

    /**
     * JSON schema Gemini must follow (structured output): an array of {id, title}. This is what makes model output
     * safe to parse in code.
     */
    public static ObjectNode responseSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "ARRAY");
        ObjectNode item = schema.putObject("items").put("type", "OBJECT");
        ObjectNode props = item.putObject("properties");
        props.putObject("id").put("type", "INTEGER");
        props.putObject("title").put("type", "STRING");
        item.putArray("required").add("id").add("title");
        return schema;
    }

    /** Returns keyword id -> cleaned title for valid entries whose id was requested. */
    public static Map<Long, String> parse(JsonNode array, List<KeywordToTitle> requested) {
        Map<Long, String> out = new HashMap<>();
        for (JsonNode n : array) {
            long id = n.path("id").asLong(-1);
            String title = SPACES.matcher(n.path("title").asText("")).replaceAll(" ").trim();
            if (title.length() >= 2 && title.length() <= MAX_TITLE_LENGTH
                    && requested.stream().anyMatch(k -> k.id() == id)) {
                out.put(id, title);
            }
        }
        return out;
    }
}
