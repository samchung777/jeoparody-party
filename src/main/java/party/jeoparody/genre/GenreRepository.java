package party.jeoparody.genre;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL for keyword genres (keyword_genre table, V9 migration). */
@Repository
public class GenreRepository {

    private final JdbcTemplate jdbc;

    public GenreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A keyword without genres and, for each category its live clues come from, how many clues. */
    public record Unclassified(long id, String name, Map<String, Integer> clueCountsByCategory) {
    }

    /** Live keywords with no genre that haven't been checked yet, with where their clues came from. */
    public List<Unclassified> unclassified() {
        Map<Long, Unclassified> byId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT k.id, k.name, c.name AS category, COUNT(*) AS n
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id AND cl.retired_at IS NULL
                JOIN category c ON c.id = cl.category_id
                WHERE k.retired_at IS NULL AND k.genres_checked_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM keyword_genre g WHERE g.keyword_id = k.id)
                GROUP BY k.id, k.name, c.name
                ORDER BY k.id
                """, rs -> {
            Unclassified u = byId.computeIfAbsent(rs.getLong("id"),
                    id -> new Unclassified(id, nameOf(rs), new HashMap<>()));
            u.clueCountsByCategory().merge(rs.getString("category"), rs.getInt("n"), Integer::sum);
        });
        return List.copyOf(byId.values());
    }

    private static String nameOf(java.sql.ResultSet rs) {
        try {
            return rs.getString("name");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unclassified keywords with enough clues to play, with sample responses, for Gemini. */
    public List<KeywordToClassify> forGemini(int minClues, int limit) {
        return jdbc.query("""
                SELECT k.id, k.name,
                       SUBSTRING_INDEX(GROUP_CONCAT(cl.jeopardy_response ORDER BY cl.id SEPARATOR '\n'), '\n', 4)
                           AS samples
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id AND cl.retired_at IS NULL AND cl.jeopardy_response IS NOT NULL
                WHERE k.retired_at IS NULL AND k.genres_checked_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM keyword_genre g WHERE g.keyword_id = k.id)
                GROUP BY k.id, k.name
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                LIMIT ?
                """,
                (rs, i) -> new KeywordToClassify(rs.getLong("id"), rs.getString("name"),
                        rs.getString("samples") == null ? List.of() : List.of(rs.getString("samples").split("\n"))),
                minClues, limit);
    }

    /** Saves genres (ignoring duplicates) and marks the keyword checked. */
    @Transactional
    public void save(long keywordId, Set<String> genres, String source) {
        for (String g : genres) {
            jdbc.update("INSERT IGNORE INTO keyword_genre (keyword_id, genre, source) VALUES (?, ?, ?)",
                    keywordId, g, source);
        }
        jdbc.update("UPDATE keyword SET genres_checked_at = CURRENT_TIMESTAMP WHERE id = ?", keywordId);
    }

    /** Genre -> number of keywords (library page). */
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT genre, COUNT(*) AS n FROM keyword_genre GROUP BY genre ORDER BY n DESC",
                rs -> {
                    out.put(rs.getString("genre"), rs.getInt("n"));
                });
        return out;
    }
}
