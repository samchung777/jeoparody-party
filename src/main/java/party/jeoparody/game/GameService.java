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
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    /** Consonants only, so room codes never spell words; no easily-confused letters. */
    private static final String CODE_LETTERS = "BCDFGHJKLMNPQRSTVWXZ";
    private static final Duration IDLE_LIMIT = Duration.ofHours(12);

    /** Returned once when a game is created; the host key is never sent anywhere else. */
    public record CreatedGame(Game game, String hostKey) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Set<String> savedResults = ConcurrentHashMap.newKeySet();
    private final GameRepository repository;
    private final Clock clock = Clock.systemUTC();

    public GameService(GameRepository repository) {
        this.repository = repository;
    }

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

    public Optional<Game> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(games.get(code.trim().toUpperCase()));
    }

    public ClueSource clueSource() {
        return repository;
    }

    /** Called after every successful action; saves the result the first time a game reaches GAME_OVER. */
    public void afterAction(Game game) {
        if (game.phase() == Phase.GAME_OVER && savedResults.add(game.code())) {
            try {
                long id = repository.saveResult(game);
                log.info("Saved result {} for game {}", id, game.code());
            } catch (RuntimeException e) {
                savedResults.remove(game.code());
                log.warn("Could not save result for game {}: {}", game.code(), e.getMessage());
            }
        }
    }

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

    String token() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return sb.toString();
    }
}
