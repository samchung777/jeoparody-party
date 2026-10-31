package party.jeoparody.game;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL-backed {@link ClueSource}, plus saving finished game results. */
@Repository
public class GameRepository implements ClueSource {

    private final JdbcTemplate jdbc;

    /**
     * JdbcTemplate comes from Spring Boot, configured by spring.datasource (local MySQL, CI sidecar or Cloud SQL).
     */
    public GameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keywords that can be a category: at least minClues usable clues (already in Jeopardy form, not retired)
     * and the keyword itself not removed by a host. One SQL query with GROUP BY / HAVING.
     */
    @Override
    public List<KeywordOption> eligibleKeywords(int minClues) {
        return jdbc.query("""
                SELECT k.id, k.name, k.title, COUNT(*) AS clue_count,
                       (SELECT GROUP_CONCAT(g.genre SEPARATOR '|') FROM keyword_genre g WHERE g.keyword_id = k.id)
                           AS genres
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id
                WHERE cl.jeopardy_clue IS NOT NULL AND cl.jeopardy_response IS NOT NULL AND cl.retired_at IS NULL
                  AND k.retired_at IS NULL
                GROUP BY k.id, k.name, k.title
                HAVING COUNT(*) >= ?
                """,
                (rs, i) -> new KeywordOption(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                        rs.getInt("clue_count"), splitGenres(rs.getString("genres"))),
                minClues);
    }

    /**
     * Every usable clue for one keyword, with all the keywords it is tagged with (hint tags on screen);
     * BoardBuilder then picks five by difficulty for a column.
     */
    @Override
    public List<CandidateClue> cluesForKeyword(long keywordId) {
        return jdbc.query("""
                SELECT cl.id, cl.difficulty, cl.jeopardy_clue, cl.jeopardy_response, cl.staleness,
                       (SELECT GROUP_CONCAT(k2.name ORDER BY k2.id SEPARATOR '|')
                        FROM clue_keyword ck2 JOIN keyword k2 ON k2.id = ck2.keyword_id
                        WHERE ck2.clue_id = cl.id AND k2.retired_at IS NULL) AS keywords
                FROM clue cl
                JOIN clue_keyword ck ON ck.clue_id = cl.id
                WHERE ck.keyword_id = ? AND cl.jeopardy_clue IS NOT NULL AND cl.jeopardy_response IS NOT NULL
                  AND cl.retired_at IS NULL
                """,
                (rs, i) -> new CandidateClue(rs.getLong("id"), rs.getString("difficulty"),
                        rs.getString("jeopardy_clue"), rs.getString("jeopardy_response"), rs.getInt("staleness"),
                        splitKeywords(rs.getString("keywords"))),
                keywordId);
    }

    /**
     * Records the final standings of a finished game.
     *
     * @Transactional: the result row and its team rows are saved together or not at all.
     */
    @Transactional
    public long saveResult(Game game) {
        List<Team> standings = game.standings();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO game_result (code, started_at, finished_at, team_count) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, game.code());
            ps.setTimestamp(2, Timestamp.from(game.startedAt()));
            ps.setTimestamp(3, Timestamp.from(game.lastActivity()));
            ps.setInt(4, standings.size());
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        for (int place = 0; place < standings.size(); place++) {
            Team t = standings.get(place);
            jdbc.update("INSERT INTO game_result_team (game_result_id, place, team_name, score) VALUES (?, ?, ?, ?)",
                    id, place + 1, t.name(), t.score());
        }
        return id;
    }

    /** Adds one to the staleness of each clue a game just showed (see Game.drainShownClueIds). */
    public void markShown(List<Long> clueIds) {
        if (clueIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE clue SET staleness = staleness + 1 WHERE id = ?",
                clueIds.stream().map(id -> new Object[] {id}).toList());
    }

    private static List<String> splitKeywords(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split("\\|"));
    }

    private static Set<String> splitGenres(String joined) {
        return joined == null || joined.isBlank() ? Set.of() : Set.of(joined.split("\\|"));
    }

    /** Number of saved games (used by tests). */
    public int countResults() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM game_result", Integer.class);
        return n == null ? 0 : n;
    }
}
