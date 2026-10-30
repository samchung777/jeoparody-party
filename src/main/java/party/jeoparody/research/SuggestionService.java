package party.jeoparody.research;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Topic suggestions from phones and the host ("Gravity Falls", "Super Powereds"). Suggestions are only stored
 * here; ResearchPlanner turns them into research when a game ends.
 *
 * Why it matters: player input is untrusted, so it is cleaned (no links, no topic-syntax characters, sensible
 * length) and rate-limited per team per game.
 */
@Service
public class SuggestionService {

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 80;
    static final int MAX_PER_TEAM_PER_GAME = 3;
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N} '&:!?.,()\\-]");
    private static final Pattern LINK = Pattern.compile("(?i)https?://|www\\.|\\.(com|net|org|io)\\b");

    private final ResearchRepository repository;
    private final Map<String, AtomicInteger> perTeam = new ConcurrentHashMap<>();

    public SuggestionService(ResearchRepository repository) {
        this.repository = repository;
    }

    /**
     * Cleans user text into a topic: strips symbols (including the [ ] { } = topic syntax), collapses spaces.
     * Throws IllegalArgumentException with a message for the user when it can't be used.
     */
    public static String clean(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Type a topic to suggest");
        }
        if (LINK.matcher(raw).find()) {
            throw new IllegalArgumentException("Suggest a topic, not a link");
        }
        String t = NOT_ALLOWED.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (t.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Type a topic to suggest");
        }
        if (t.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Keep it under " + MAX_LENGTH + " characters");
        }
        return t;
    }

    /**
     * Saves a suggestion. {@code who} identifies the suggester within the game (team id or "host") for the rate
     * limit; {@code name} is stored for the library page. Returns the cleaned topic.
     */
    public String submit(String raw, String gameCode, int gameNumber, String who, String name) {
        String topic = clean(raw);
        String key = gameCode + "#" + gameNumber + "#" + who;
        if (perTeam.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > MAX_PER_TEAM_PER_GAME) {
            throw new IllegalArgumentException("Thanks! That's " + MAX_PER_TEAM_PER_GAME + " suggestions for this game");
        }
        repository.suggest(topic, gameCode, name);
        return topic;
    }
}
