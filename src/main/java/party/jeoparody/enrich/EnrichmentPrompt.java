package party.jeoparody.enrich;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the Gemini prompt and validates its JSON reply. No I/O, so it is unit-testable.
 */
public final class EnrichmentPrompt {

    static final int MAX_KEYWORDS = 3;
    static final int MAX_KEYWORD_LENGTH = 40;
    private static final Pattern RESPONSE_FORM = Pattern.compile(
            "(?i)^(what|who|where|when|which|how)\\b.*\\?$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern KEYWORD_JUNK = Pattern.compile("[^\\p{L}\\p{N} &'\\-]");

    private EnrichmentPrompt() {
    }

    public static String build(ObjectMapper mapper, List<ClueToEnrich> batch, List<String> existingKeywords) {
        ArrayNode items = mapper.createArrayNode();
        for (ClueToEnrich c : batch) {
            ObjectNode n = items.addObject();
            n.put("id", c.id());
            n.put("category", c.category());
            n.put("difficulty", c.difficulty());
            n.put("question", c.question());
            n.put("answer", c.answer());
        }
        String keywords = existingKeywords.isEmpty() ? "(none yet)" : String.join(", ", existingKeywords);
        return """
                You are a writer for the TV quiz show Jeopardy!. Convert each trivia item below.

                For every item return:
                - "id": the item's id, unchanged.
                - "clue": the fact rewritten as a Jeopardy!-style statement that the contestant must respond to.
                  Use "this"/"these" phrasing (e.g. "This planet is known as the Red Planet").
                  Never include the answer in the clue. Keep it under 30 words. Do not add facts that are not
                  implied by the question and answer.
                - "response": the correct answer phrased as a question, e.g. "What is Mars?", "Who is Ada Lovelace?",
                  "What are the Andes?", "Where is Peru?". Keep the answer's wording.
                - "keywords": 2 or 3 subject keywords that could serve as a Jeopardy! category name for this clue:
                  one broad subject (e.g. "Astronomy", "World Capitals", "80s Music") and one or two narrower ones.
                  Title Case, 1-3 words, no punctuation, and never the answer itself.
                  REUSE an existing keyword whenever one fits, so categories collect enough clues.

                Existing keywords: %s

                Items:
                %s

                Reply with a JSON array containing exactly one object per item.
                """.formatted(keywords, items.toPrettyString());
    }

    /** JSON schema passed to Gemini as responseSchema (OpenAPI subset used by Vertex AI). */
    public static ObjectNode responseSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "ARRAY");
        ObjectNode item = schema.putObject("items").put("type", "OBJECT");
        ObjectNode props = item.putObject("properties");
        props.putObject("id").put("type", "INTEGER");
        props.putObject("clue").put("type", "STRING");
        props.putObject("response").put("type", "STRING");
        props.putObject("keywords").put("type", "ARRAY").putObject("items").put("type", "STRING");
        item.putArray("required").add("id").add("clue").add("response").add("keywords");
        return schema;
    }

    /**
     * Parses Gemini's JSON array, keeping only well-formed results for ids that were in the batch.
     */
    public static List<EnrichedClue> parse(JsonNode array, List<ClueToEnrich> batch) {
        Map<Long, ClueToEnrich> byId = new LinkedHashMap<>();
        batch.forEach(c -> byId.put(c.id(), c));

        List<EnrichedClue> out = new ArrayList<>();
        for (JsonNode n : array) {
            ClueToEnrich source = byId.remove(n.path("id").asLong(-1));
            if (source == null) {
                continue;
            }
            String clue = clean(n.path("clue").asText());
            String response = clean(n.path("response").asText());
            if (clue.isEmpty() || response.isEmpty()) {
                continue;
            }
            if (!RESPONSE_FORM.matcher(response).matches()) {
                response = toQuestion(source.answer());
            }
            List<String> keywords = cleanKeywords(n.path("keywords"), source.answer());
            if (keywords.isEmpty()) {
                continue;
            }
            out.add(new EnrichedClue(source.id(), clue, response, keywords));
        }
        return out;
    }

    /** Fallback when the model's response isn't phrased as a question. */
    static String toQuestion(String answer) {
        String a = clean(answer);
        return "What is " + a + "?";
    }

    static List<String> cleanKeywords(JsonNode node, String answer) {
        Map<String, String> unique = new LinkedHashMap<>();
        Set<String> banned = Set.of(normalize(answer));
        for (JsonNode k : node) {
            String keyword = clean(KEYWORD_JUNK.matcher(k.asText()).replaceAll(""));
            if (keyword.isEmpty() || keyword.length() > MAX_KEYWORD_LENGTH || banned.contains(normalize(keyword))) {
                continue;
            }
            unique.putIfAbsent(normalize(keyword), keyword);
            if (unique.size() == MAX_KEYWORDS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private static String clean(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll(" ").trim();
    }

    private static String normalize(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }
}
