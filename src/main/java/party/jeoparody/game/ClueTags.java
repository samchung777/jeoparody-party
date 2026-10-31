package party.jeoparody.game;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks which of a clue's keywords the screens may show as hints without spoiling the answer. No I/O.
 *
 * A keyword is hidden when it is the category itself (already on screen), when it appears in the correct
 * response or the response appears in it ("Aaron Burr" for "Who is Burr?"), or when it shares a distinctive
 * word with the response ("Skywalker Family" for "Who is Luke Skywalker?").
 */
public final class ClueTags {

    /** At most this many hint tags per clue, so the screen stays readable. */
    public static final int MAX_TAGS = 4;

    /** Words too common to count as giving the answer away. */
    private static final Set<String> COMMON = Set.of("what", "who", "where", "when", "which", "how", "is", "are",
            "was", "were", "the", "a", "an", "of", "and", "or", "in", "on", "to", "for", "with", "by", "at", "from",
            "his", "her", "its", "their", "this", "that", "these", "those", "part", "season", "book", "film",
            "movie", "game", "series", "episode", "chapter", "volume", "first", "second", "third");

    /** Static helpers only. */
    private ClueTags() {
    }

    /** The keywords that are safe to show for this clue, in their original order, at most MAX_TAGS. */
    public static List<String> safe(List<String> keywords, String response, String categoryKeyword) {
        String answer = normalize(stripQuestion(response));
        Set<String> answerWords = distinctiveWords(answer);
        String category = normalize(categoryKeyword);
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keywords) {
            String key = normalize(k);
            if (key.isEmpty() || key.equals(category) || out.containsKey(key)) {
                continue;
            }
            if (!answer.isEmpty() && (contains(answer, key) || contains(key, answer))) {
                continue;
            }
            if (distinctiveWords(key).stream().anyMatch(answerWords::contains)) {
                continue;
            }
            out.put(key, k.strip());
            if (out.size() == MAX_TAGS) {
                break;
            }
        }
        return new ArrayList<>(out.values());
    }

    /** "Who is Aaron Burr?" -> "Aaron Burr". */
    static String stripQuestion(String response) {
        if (response == null) {
            return "";
        }
        return response.strip()
                .replaceFirst("(?i)^(what|who|where|when|which|how)\\s+(is|are|was|were)\\s+", "")
                .replaceFirst("\\?$", "");
    }

    /** Lower case, accents removed, punctuation to spaces, single spaces. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String plain = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Whole-word containment, so "art" is not found inside "heart". */
    private static boolean contains(String haystack, String needle) {
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    /** Words of at least four letters that aren't common filler. */
    private static Set<String> distinctiveWords(String normalized) {
        return Arrays.stream(normalized.split(" "))
                .filter(w -> w.length() >= 4 && !COMMON.contains(w))
                .collect(Collectors.toSet());
    }
}
