package party.jeoparody.wiki;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Prompt for writing clues from an article, and the strict validation of Gemini's reply.
 * A clue survives only if its quoted evidence really appears in the article. No I/O.
 */
public final class WikiCluePrompt {

    static final int MIN_QUOTE_LENGTH = 25;
    static final int MAX_KEYWORDS = 3;
    static final int MAX_CLUE_LENGTH = 400;
    private static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");
    private static final Pattern RESPONSE_FORM = Pattern.compile("(?i)^(what|who|where|when|which|how)\\b.*\\?$");

    private WikiCluePrompt() {
    }

    public static String build(String topicKeyword, WikiPage page, int count) {
        return """
                You are a writer for the TV quiz show Jeopardy!. Write up to %d clues for a category about
                "%s", using ONLY facts stated in the Wikipedia article below.

                Mix difficulties: about a third "easy" (things a casual fan knows), a third "medium",
                a third "hard" (deep cuts a superfan would know).

                For each clue return:
                - "difficulty": "easy", "medium" or "hard".
                - "clue": a Jeopardy!-style statement using "this"/"these" phrasing, under 30 words.
                  It must not contain the answer or an obvious part of it.
                - "answer": the short answer, 1-6 words. Never "%s" itself.
                - "response": the answer phrased as a question, e.g. "Who is Aaron Burr?", "What is Midgar?".
                - "quote": copy one sentence (at least 8 words) EXACTLY, word for word, from the article that
                  proves the answer. Do not paraphrase it.
                - "keywords": 1 or 2 narrower subjects for this clue, Title Case, 1-4 words (for example a specific
                  game or film in a series, a character, an album, a song, a cast member, a place).

                Rules: every clue needs a different answer; prefer people, characters, places, titles and
                memorable details over dates and sales figures; nothing that only makes sense with a picture;
                family friendly.

                Article title: %s

                Article:
                <<<
                %s
                >>>

                Reply with a JSON array of clue objects.
                """.formatted(count, topicKeyword, topicKeyword, page.title(), page.text());
    }

    public static ObjectNode responseSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "ARRAY");
        ObjectNode item = schema.putObject("items").put("type", "OBJECT");
        ObjectNode p = item.putObject("properties");
        p.putObject("difficulty").put("type", "STRING");
        p.putObject("clue").put("type", "STRING");
        p.putObject("answer").put("type", "STRING");
        p.putObject("response").put("type", "STRING");
        p.putObject("quote").put("type", "STRING");
        p.putObject("keywords").put("type", "ARRAY").putObject("items").put("type", "STRING");
        item.putArray("required").add("difficulty").add("clue").add("answer").add("response").add("quote")
                .add("keywords");
        return schema;
    }

    /** Keeps only clues that are well-formed, verifiable against the article, and not giveaways. */
    public static List<GeneratedClue> parse(JsonNode array, WikiPage page, String topicKeyword) {
        String article = WikiText.normalize(page.text());
        String topicKey = WikiText.answerKey(topicKeyword);
        Set<String> answersSeen = new HashSet<>();
        List<GeneratedClue> out = new ArrayList<>();
        for (JsonNode n : array) {
            String difficulty = n.path("difficulty").asText("").trim().toLowerCase();
            String clue = n.path("clue").asText("").strip();
            String answer = n.path("answer").asText("").strip();
            String response = n.path("response").asText("").strip();
            String quote = n.path("quote").asText("").strip();
            String answerKey = WikiText.answerKey(answer);

            if (!DIFFICULTIES.contains(difficulty) || clue.isEmpty() || clue.length() > MAX_CLUE_LENGTH
                    || answerKey.isEmpty()) {
                continue;
            }
            if (answerKey.equals(topicKey) || answersSeen.contains(answerKey)) {
                continue; // "What is Final Fantasy?" in a Final Fantasy category, or a repeated answer
            }
            if (WikiText.givesAwayAnswer(clue, answer)) {
                continue;
            }
            if (!WikiText.quoteAppearsIn(quote, article, MIN_QUOTE_LENGTH)) {
                continue; // can't prove it from the article: possibly invented
            }
            if (!RESPONSE_FORM.matcher(response).matches()) {
                response = "What is " + answer + "?";
            }
            answersSeen.add(answerKey); // only accepted clues claim an answer
            out.add(new GeneratedClue(difficulty, clue, response, answer, quote, keywords(n.path("keywords"),
                    topicKeyword, answerKey)));
        }
        return out;
    }

    /** The topic first, then up to two narrower keywords that aren't the answer or the topic again. */
    static List<String> keywords(JsonNode extra, String topicKeyword, String answerKey) {
        Map<String, String> unique = new LinkedHashMap<>();
        unique.put(WikiText.answerKey(topicKeyword), WikiText.keyword(topicKeyword));
        for (JsonNode k : extra) {
            String keyword = WikiText.keyword(k.asText(""));
            String key = WikiText.answerKey(keyword);
            if (keyword.isEmpty() || key.equals(answerKey)) {
                continue;
            }
            unique.putIfAbsent(key, keyword);
            if (unique.size() == MAX_KEYWORDS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }
}
