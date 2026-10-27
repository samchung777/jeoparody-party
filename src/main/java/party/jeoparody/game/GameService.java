package party.jeoparody.game;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps live games in memory (the app runs as a single Cloud Run instance) and
 * saves results to MySQL when a game ends.
 *
 * Where it fits: GameController (REST) creates games here; GameSocketHandler finds them here for every action.
 * Why it matters: because games live in this map, the Cloud Run service is limited to one instance with session
 * affinity. Scaling out would need shared state (for example Redis) so any instance could serve any game.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    /** Consonants only, so room codes never spell words; no easily-confused letters. */
    private static final String CODE_LETTERS = "BCDFGHJKLMNPQRSTVWXZ";
    /** Rooms untouched for 12 hours are removed, so memory doesn't grow forever. */
    private static final Duration IDLE_LIMIT = Duration.ofHours(12);

    /** Returned once when a game is created; the host key is never sent anywhere else. */
    public record CreatedGame(Game game, String hostKey) {
    }

    /**
     * Cryptographically strong randomness: room codes, host keys and team tokens must not be guessable.
     */
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Set<String> savedResults = ConcurrentHashMap.newKeySet();
    private final GameRepository repository;
    private final Clock clock = Clock.systemUTC();

    /** The repository is the ClueSource (clues from MySQL) and saves finished results. */
    public GameService(GameRepository repository) {
        this.repository = repository;
    }

    /**
     * Makes a new room: a random 4-letter code (retrying on the rare clash) and a secret host key. Each game gets its
     * own random BoardBuilder for picking clues and Daily Doubles.
     */
    public CreatedGame create() {
        String hostKey = token();
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = randomCode();
            Game game = new Game(code, hostKey, new BoardBuilder(new Random(random.nextLong())), this::token, clock);
            if (games.putIfAbsent(code, game) == null) {
                log.info("Created game {}", code);
                return new CreatedGame(game, hostKey);
            }
        }
        throw new IllegalStateException("Could not allocate a room code");
    }

    /** Looks up a room by code, case-insensitive (players type it on phones). */
    public Optional<Game> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(games.get(code.trim().toUpperCase()));
    }

    /** Gives Game access to clues without Game depending on Spring or JDBC. */
    public ClueSource clueSource() {
        return repository;
    }

    /** Called after every successful action; saves the result the first time a game reaches GAME_OVER. */
    public void afterAction(Game game) {
        String resultKey = game.code() + "#" + game.gameNumber(); // a room can play several games in a row
        if (game.phase() == Phase.GAME_OVER && savedResults.add(resultKey)) {
            try {
                long id = repository.saveResult(game);
                log.info("Saved result {} for game {}", id, game.code());
            } catch (RuntimeException e) {
                savedResults.remove(resultKey);
                log.warn("Could not save result for game {}: {}", game.code(), e.getMessage());
            }
        }
    }

    /** Scheduled clean-up every 30 minutes (Spring @Scheduled). */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void removeIdleGames() {
        Instant cutoff = clock.instant().minus(IDLE_LIMIT);
        games.values().removeIf(g -> {
            boolean idle = g.lastActivity().isBefore(cutoff);
            if (idle) {
                log.info("Removing idle game {}", g.code());
            }
            return idle;
        });
    }

    /** 128 random bits, URL-safe Base64: host keys and team tokens. */
    String token() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Four letters from CODE_LETTERS: about 160,000 possible room codes. */
    private String randomCode() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return sb.toString();
    }
}
