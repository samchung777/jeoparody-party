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

    /** Bump when the prompt changes so existing clues are re-tagged. */
    public static final int VERSION = 2;
    static final int MAX_KEYWORDS = 3;
    static final int MAX_KEYWORD_LENGTH = 40;
    private static final Pattern RESPONSE_FORM = Pattern.compile(
            "(?i)^(what|who|where|when|which|how)\\b.*\\?$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern KEYWORD_JUNK = Pattern.compile("[^\\p{L}\\p{N} &'\\-]");

    /** Static helpers only: no instances. */
    private EnrichmentPrompt() {
    }

    /**
     * Writes the prompt: the Jeopardy rules (clue as a statement, response as a question, exactly 3 keywords),
     * the batch as JSON, and the most-used existing keywords so categories converge instead of multiplying.
     */
    public static String build(ObjectMapper mapper, List<ClueToEnrich> batch, List<String> existingKeywords) {
        ArrayNode items = mapper.createArrayNode();
        for (ClueToEnrich c : batch) {
            ObjectNode n = items.addObject();
            n.put("id", c.id());
            n.put("category", c.category());
            n.put("difficulty", c.difficulty());
            n.put("question", c.question());
            n.put("answer", c.answer());
            if (c.hostFeedback() != null && !c.hostFeedback().isBlank()) {
                n.put("hostFeedback", c.hostFeedback());
            }
        }
        String keywords = existingKeywords.isEmpty() ? "(none yet)" : String.join(", ", existingKeywords);
        return """
                You are a writer for the TV quiz show Jeopardy! preparing a pop-culture themed game.
                Convert each trivia item below.

                For every item return:
                - "id": the item's id, unchanged.
                - "clue": the fact rewritten as a Jeopardy!-style statement that the contestant must respond to.
                  Use "this"/"these" phrasing (e.g. "This 1985 film sent Marty McFly back in time in a DeLorean").
                  Never include the answer in the clue. Keep it under 30 words. Do not add facts that are not
                  implied by the question and answer.
                - "response": the correct answer phrased as a question, e.g. "What is Mars?", "Who is Lin-Manuel Miranda?",
                  "What is Hamilton?", "What are the Beatles?". Keep the answer's wording.
                - "keywords": exactly 3 keywords that could each serve as a Jeopardy! category for this clue,
                  ordered from broadest to most specific. Title Case, 1-4 words, no punctuation except "&",
                  and never the answer itself.
                    1. A THEME that many clues could share, as specific as the clue allows. Prefer medium + decade
                       or medium + genre: "80s Movies", "90s Music", "2000s TV", "Broadway Musicals",
                       "Classic Rock", "90s Hip Hop", "Sitcoms", "Animated Movies", "Retro Video Games",
                       "Anime", "Superheroes", "Board Games", "Celebrities", "Memes & Internet Culture".
                       Write decades as "60s", "70s", "80s", "90s", "2000s", "2010s", "2020s" and only use a decade
                       when you are confident of the year. Non-pop-culture clues get a plain subject
                       ("World Geography", "Space").
                    2. A franchise, creator, artist or series: "Star Wars", "Pokemon", "Disney Animation",
                       "Taylor Swift", "Nintendo", "Stephen King", "Marvel", "The Simpsons".
                    3. Something more specific still: a character, album, era, award or sub-genre
                       ("Pixar Villains", "Grammy Winners", "Tony Award Winners", "Boy Bands").
                  REUSE an existing keyword whenever one fits, so categories collect enough clues.
                - If an item has "hostFeedback", a game host complained about the previous version of this clue.
                  Write a clearly better clue that fixes the problem they describe.

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

    /**
     * Validates the model's keywords: strips odd characters, drops duplicates, overly long ones and any keyword
     * equal to the answer (a category must not give the answer away).
     */
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

    /** Collapses whitespace. */
    private static String clean(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** Case-insensitive comparison key. */
    private static String normalize(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }
}
