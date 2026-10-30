package party.jeoparody.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import party.jeoparody.game.Themes;

/**
 * Prompt and parser for sorting keywords into the fixed general categories. The response schema restricts genres
 * to {@link Themes#GENRES} (an enum), and the parser drops anything else. No I/O, unit-tested.
 */
public final class GenrePrompt {

    static final int MAX_GENRES = 2;

    private GenrePrompt() {
    }

    public static String build(ObjectMapper mapper, List<KeywordToClassify> keywords) {
        ArrayNode items = mapper.createArrayNode();
        for (KeywordToClassify k : keywords) {
            ObjectNode n = items.addObject();
            n.put("id", k.id());
            n.put("keyword", k.keyword());
            ArrayNode samples = n.putArray("sampleResponses");
            k.sampleResponses().forEach(samples::add);
        }
        return """
                You sort pop-culture trivia categories for a Jeopardy!-style party game into general categories.
                For each keyword give 1 or 2 genres from this list only: %s.
                Choose by what the trivia is about: "Naruto" is Anime & Manga, "Hamilton" is Musicals & Theatre,
                "Taylor Swift" is Music, "SpongeBob SquarePants" is Cartoons, "The Hunger Games" is Books and Movies.
                Use the sample responses to tell what the keyword covers.

                Keywords:
                %s

                Reply with a JSON array of {"id", "genres"} objects, one per keyword.
                """.formatted(String.join(", ", Themes.GENRES), items.toPrettyString());
    }

    public static ObjectNode responseSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "ARRAY");
        ObjectNode item = schema.putObject("items").put("type", "OBJECT");
        ObjectNode props = item.putObject("properties");
        props.putObject("id").put("type", "INTEGER");
        ObjectNode genreItems = props.putObject("genres").put("type", "ARRAY").putObject("items").put("type", "STRING");
        ArrayNode allowed = genreItems.putArray("enum");
        Themes.GENRES.forEach(allowed::add);
        item.putArray("required").add("id").add("genres");
        return schema;
    }

    /** id -> genres, for requested ids only; unknown genres dropped, at most 2, first answer per id wins. */
    public static Map<Long, Set<String>> parse(JsonNode reply, List<KeywordToClassify> requested) {
        Set<Long> ids = new java.util.HashSet<>();
        requested.forEach(k -> ids.add(k.id()));
        Map<Long, Set<String>> out = new HashMap<>();
        for (JsonNode item : reply) {
            long id = item.path("id").asLong(-1);
            if (!ids.contains(id) || out.containsKey(id)) {
                continue;
            }
            Set<String> genres = new LinkedHashSet<>();
            for (JsonNode g : item.path("genres")) {
                if (Themes.GENRES.contains(g.asText()) && genres.size() < MAX_GENRES) {
                    genres.add(g.asText());
                }
            }
            out.put(id, genres);
        }
        return out;
    }
}
