package party.jeoparody.game;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL-backed {@link ClueSource}, plus saving finished game results. */
@Repository
public class GameRepository implements ClueSource {

    private final JdbcTemplate jdbc;

    public GameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<KeywordOption> eligibleKeywords(int minClues) {
        return jdbc.query("""
                SELECT k.id, k.name, k.title, COUNT(*) AS clue_count
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id
                WHERE cl.jeopardy_clue IS NOT NULL AND cl.jeopardy_response IS NOT NULL AND cl.retired_at IS NULL
                  AND k.retired_at IS NULL
                GROUP BY k.id, k.name, k.title
                HAVING COUNT(*) >= ?
                """,
                (rs, i) -> new KeywordOption(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                        rs.getInt("clue_count")),
                minClues);
    }

    @Override
    public List<CandidateClue> cluesForKeyword(long keywordId) {
        return jdbc.query("""
                SELECT cl.id, cl.difficulty, cl.jeopardy_clue, cl.jeopardy_response
                FROM clue cl
                JOIN clue_keyword ck ON ck.clue_id = cl.id
                WHERE ck.keyword_id = ? AND cl.jeopardy_clue IS NOT NULL AND cl.jeopardy_response IS NOT NULL
                  AND cl.retired_at IS NULL
                """,
                (rs, i) -> new CandidateClue(rs.getLong("id"), rs.getString("difficulty"),
                        rs.getString("jeopardy_clue"), rs.getString("jeopardy_response")),
                keywordId);
    }

    /** Records the final standings of a finished game. */
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

    public int countResults() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM game_result", Integer.class);
        return n == null ? 0 : n;
    }
}
