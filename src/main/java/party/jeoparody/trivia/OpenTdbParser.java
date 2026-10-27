package party.jeoparody.trivia;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure parsing logic for Open Trivia DB (https://opentdb.com) JSON responses.
 * Kept free of I/O so it can be unit tested without the network.
 */
public final class OpenTdbParser {

    /** Response codes documented by Open Trivia DB. */
    public static final int SUCCESS = 0;
    public static final int NO_RESULTS = 1;
    public static final int TOKEN_NOT_FOUND = 3;
    public static final int TOKEN_EMPTY = 4;
    public static final int RATE_LIMITED = 5;

    private static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");

    /** Questions that only make sense when the options are shown don't work as Jeopardy clues. */
    private static final Pattern NEEDS_OPTIONS = Pattern.compile(
            "(?i)\\b(which|what)( one)? of (the following|these|those)\\b");

    /**
     * Static helpers only: pure parsing, unit-tested by OpenTdbParserTest with saved API responses.
     */
    private OpenTdbParser() {
    }

    /**
     * Open Trivia DB's own status code (0 = success, others = no results, token exhausted, rate limited...).
     */
    public static int responseCode(JsonNode root) {
        return root.path("response_code").asInt(-1);
    }

    /** Parses /api.php results requested with {@code encode=url3986}, skipping unusable questions. */
    public static List<ParsedQuestion> parseQuestions(JsonNode root) {
        List<ParsedQuestion> out = new ArrayList<>();
        for (JsonNode n : root.path("results")) {
            if (!"multiple".equals(decode(n.path("type").asText()))) {
                continue;
            }
            String question = decode(n.path("question").asText()).trim();
            String answer = decode(n.path("correct_answer").asText()).trim();
            String difficulty = decode(n.path("difficulty").asText()).toLowerCase(Locale.ROOT);
            String category = decode(n.path("category").asText()).trim();

            if (question.isEmpty() || answer.isEmpty() || !DIFFICULTIES.contains(difficulty)) {
                continue;
            }
            if (NEEDS_OPTIONS.matcher(question).find()) {
                continue;
            }
            out.add(new ParsedQuestion(category, difficulty, question, answer));
        }
        return out;
    }

    /** Parses /api_category.php into id -> name. */
    public static Map<Integer, String> parseCategories(JsonNode root) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (JsonNode n : root.path("trivia_categories")) {
            out.put(n.path("id").asInt(), n.path("name").asText());
        }
        return out;
    }

    /** "Entertainment: Video Games" -> "Video Games". */
    public static String displayName(String rawCategory) {
        int colon = rawCategory.indexOf(':');
        return (colon >= 0 ? rawCategory.substring(colon + 1) : rawCategory).trim();
    }

    /** Undoes the URL encoding requested with encode=url3986. */
    static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
