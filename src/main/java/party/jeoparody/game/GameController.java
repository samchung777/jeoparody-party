package party.jeoparody.game;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for games (plain request/response, used where live updates aren't needed).
 * Where it fits: index.html calls POST /api/games to create a room; play.html checks a code with GET before
 * opening the WebSocket. The smoke test calls both, so a broken controller fails the pipeline.
 */
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;

    /** Spring injects the GameService that holds the live games. */
    public GameController(GameService games) {
        this.games = games;
    }

    /** Creates a room. The host key only ever appears in this response (it goes into the host's link). */
    @PostMapping
    public ResponseEntity<Map<String, String>> create() {
        GameService.CreatedGame created = games.create();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", created.game().code(), "hostKey", created.hostKey()));
    }

    /** Lets the join page check a room code before connecting. */
    @GetMapping("/{code}")
    public ResponseEntity<Map<String, String>> get(@PathVariable String code) {
        return games.find(code)
                .map(g -> ResponseEntity.ok(Map.of("code", g.code(), "phase", g.phase().name())))
                .orElse(ResponseEntity.notFound().build());
    }
}
