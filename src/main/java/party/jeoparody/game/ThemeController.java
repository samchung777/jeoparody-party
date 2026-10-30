package party.jeoparody.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/themes: every theme with how many playable categories it has right now, for the host's theme menu.
 * A theme needs at least 13 (12 for the boards plus one for Final Jeopardy) to start a game.
 */
@RestController
public class ThemeController {

    private final GameRepository repository;

    public ThemeController(GameRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/themes")
    public List<Map<String, Object>> themes() {
        List<KeywordOption> playable = repository.eligibleKeywords(Game.MIN_CLUES_PER_KEYWORD);
        return Themes.all().stream().map(t -> {
            long categories = playable.stream().filter(k -> t.matches(k.genres())).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("genres", t.genres().stream().sorted().toList());
            m.put("categories", categories);
            m.put("playable", categories >= Game.BOARD_CATEGORIES + 1);
            return m;
        }).toList();
    }
}
