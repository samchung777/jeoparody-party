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

    /** Bump when the prompt changes: topics done with an older version are regenerated. */
    public static final int VERSION = 2;
    static final int MIN_QUOTE_LENGTH = 25;
    static final int MAX_NARROW_KEYWORDS = 2;
    static final int MAX_CLUE_LENGTH = 400;
    static final int MAX_EXCLUDED_IN_PROMPT = 150;
    private static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");
    private static final Pattern RESPONSE_FORM = Pattern.compile("(?i)^(what|who|where|when|which|how)\\b.*\\?$");

    private WikiCluePrompt() {
    }

    public static String build(WikiText.TopicSpec topic, WikiPage page, int count, Set<String> excludedAnswers) {
        String keyword = topic.keyword();
        String focus = topic.focus() == null ? "" : "\nThe host asked for this focus: " + topic.focus() + "\n";
        String avoid = excludedAnswers.isEmpty() ? "" : "\nThese answers are already used; do not use them again: "
                + String.join("; ", excludedAnswers.stream().limit(MAX_EXCLUDED_IN_PROMPT).toList()) + "\n";
        return """
                You are a writer for the TV quiz show Jeopardy!, writing a category about "%s" for fans of it at
                a party. Write up to %d clues using ONLY facts stated in the Wikipedia text below.

                WRITE ABOUT what fans remember and love:
                - the plot and story events, twists and endings
                - main and supporting characters: names, roles, relationships, powers/abilities, signature lines
                - the main cast and voice actors, and which character each one plays
                - memorable songs (for musicals and films: the song title, who sings it, the moment it's sung)
                - in-universe places, items, creatures, teams, factions and lore
                - for games: playable characters, worlds, items, mechanics and story
                - for scripture: the people, stories, events and teachings
                %s
                NEVER WRITE ABOUT how or by whom it was made or sold:
                - directors, writers, authors, producers, composers, lyricists, designers, developers, studios,
                  publishers, networks or record labels
                - development, production, filming, recording, casting decisions, budgets
                - release dates or years, platforms, sales, box office, ratings, reviews, awards or nominations
                - the real-world year or city a story is set in or was made in
                %s
                Mix difficulties: about a third "easy" (a casual fan knows it), a third "medium", a third "hard"
                (a superfan knows it). Hard means deeper in the story, not behind the scenes.

                For each clue return:
                - "difficulty": "easy", "medium" or "hard".
                - "clue": a Jeopardy!-style statement using "this"/"these" phrasing, under 30 words.
                  It must not contain the answer or an obvious part of it.
                - "answer": the short answer, 1-6 words. Never "%s" itself.
                - "response": the answer phrased as a question, e.g. "Who is Aaron Burr?", "What is Midgar?".
                - "quote": copy one sentence (at least 8 words) EXACTLY, word for word, from the text that
                  proves the answer. Do not paraphrase it.
                - "keywords": 1 or 2 narrower subjects for this clue, Title Case, 1-4 words (a specific game,
                  film, season, arc, character, song or place).

                Every clue needs a different answer. Nothing that only makes sense with a picture. Family friendly.

                Article title: %s

                Text:
                <<<
                %s
                >>>

                Reply with a JSON array of clue objects.
                """.formatted(keyword, count, focus, avoid, keyword, page.title(), page.text());
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

    /**
     * Keeps only clues that are well-formed, verifiable against the article, not giveaways and not repeats
     * of answers already used for this page.
     */
    public static List<GeneratedClue> parse(JsonNode array, WikiPage page, WikiText.TopicSpec topic,
            Set<String> excludedAnswers) {
        String article = WikiText.normalize(page.text());
        String topicKey = WikiText.answerKey(topic.keyword());
        Set<String> answersSeen = new HashSet<>(excludedAnswers);
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
                continue; // the topic itself, or an answer already used
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
            out.add(new GeneratedClue(difficulty, clue, response, answer, quote,
                    keywords(n.path("keywords"), topic, answerKey)));
        }
        return out;
    }

    /** The topic, then its groups, then up to two narrower keywords that aren't the answer or a repeat. */
    static List<String> keywords(JsonNode extra, WikiText.TopicSpec topic, String answerKey) {
        Map<String, String> unique = new LinkedHashMap<>();
        unique.put(WikiText.answerKey(topic.keyword()), WikiText.keyword(topic.keyword()));
        topic.groups().forEach(g -> unique.putIfAbsent(WikiText.answerKey(g), g));
        int narrow = 0;
        for (JsonNode k : extra) {
            String keyword = WikiText.keyword(k.asText(""));
            String key = WikiText.answerKey(keyword);
            if (keyword.isEmpty() || key.equals(answerKey) || unique.containsKey(key)) {
                continue;
            }
            unique.put(key, keyword);
            if (++narrow == MAX_NARROW_KEYWORDS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }
}
