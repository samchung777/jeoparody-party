package party.jeoparody.trivia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC access to the category and clue tables. */
@Repository
public class TriviaRepository {

    private final JdbcTemplate jdbc;

    /** Plain SQL through JdbcTemplate. */
    public TriviaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the category if new (or renames it), and returns its id. */
    public long upsertCategory(String source, int sourceId, String name) {
        jdbc.update("""
                INSERT INTO category (source, source_id, name) VALUES (?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE name = new.name
                """, source, sourceId, name);
        return jdbc.queryForObject(
                "SELECT id FROM category WHERE source = ? AND source_id = ?", Long.class, source, sourceId);
    }

    /** Inserts a clue unless the same question text is already stored. Returns true if it was added. */
    public boolean insertClueIfNew(long categoryId, String difficulty, String question, String answer) {
        int rows = jdbc.update("""
                INSERT IGNORE INTO clue (category_id, difficulty, question, answer, question_hash)
                VALUES (?, ?, ?, ?, ?)
                """, categoryId, difficulty, question, answer, hash(question));
        return rows == 1;
    }

    /** Categories with live clue counts per difficulty (library page). */
    public List<Category> listCategories() {
        return jdbc.query("""
                SELECT c.id, c.name,
                       COALESCE(SUM(cl.difficulty = 'easy'), 0)   AS easy,
                       COALESCE(SUM(cl.difficulty = 'medium'), 0) AS medium,
                       COALESCE(SUM(cl.difficulty = 'hard'), 0)   AS hard
                FROM category c
                LEFT JOIN clue cl ON cl.category_id = c.id AND cl.retired_at IS NULL
                GROUP BY c.id, c.name
                ORDER BY c.name
                """,
                (rs, i) -> new Category(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("easy"), rs.getInt("medium"), rs.getInt("hard")));
    }

    /** A category's clues with their keywords joined into one string, easy first (library page). */
    public List<Clue> findClues(long categoryId, int limit) {
        return jdbc.query("""
                SELECT cl.id, cl.category_id, cl.difficulty, cl.question, cl.answer,
                       cl.jeopardy_clue, cl.jeopardy_response,
                       GROUP_CONCAT(k.name ORDER BY k.name SEPARATOR '|') AS keywords
                FROM clue cl
                LEFT JOIN clue_keyword ck ON ck.clue_id = cl.id
                LEFT JOIN keyword k ON k.id = ck.keyword_id
                WHERE cl.category_id = ? AND cl.retired_at IS NULL
                GROUP BY cl.id
                ORDER BY FIELD(cl.difficulty, 'easy', 'medium', 'hard'), cl.id
                LIMIT ?
                """,
                (rs, i) -> new Clue(rs.getLong("id"), rs.getLong("category_id"), rs.getString("difficulty"),
                        rs.getString("question"), rs.getString("answer"),
                        rs.getString("jeopardy_clue"), rs.getString("jeopardy_response"),
                        splitKeywords(rs.getString("keywords"))),
                categoryId, limit);
    }

    /** Keywords with at least {@code minClues} enriched clues, most-used first. */
    public List<KeywordStats> keywordStats(int minClues, int limit) {
        return jdbc.query("""
                SELECT k.id, k.name, k.title, COUNT(*) AS total,
                       SUM(cl.difficulty = 'easy')   AS easy,
                       SUM(cl.difficulty = 'medium') AS medium,
                       SUM(cl.difficulty = 'hard')   AS hard
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id
                WHERE cl.retired_at IS NULL AND cl.jeopardy_clue IS NOT NULL AND k.retired_at IS NULL
                GROUP BY k.id, k.name, k.title
                HAVING COUNT(*) >= ?
                ORDER BY total DESC, k.name
                LIMIT ?
                """,
                (rs, i) -> new KeywordStats(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                        rs.getInt("total"),
                        rs.getInt("easy"), rs.getInt("medium"), rs.getInt("hard")),
                minClues, limit);
    }

    /** Splits the GROUP_CONCAT result back into a list. */
    private static List<String> splitKeywords(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.of(joined.split("\\|"));
    }

    /** Total clues; 0 means a fresh database (triggers the start-up import when enabled). */
    public long countClues() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM clue", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * SHA-256 of the normalized question text. The unique key on question_hash is how re-imports skip duplicates,
     * and why a retired clue can never be re-imported as new.
     */
    static String hash(String question) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(question.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
