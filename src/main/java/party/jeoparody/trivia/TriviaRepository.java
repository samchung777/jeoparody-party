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

    public List<Category> listCategories() {
        return jdbc.query("""
                SELECT c.id, c.name,
                       COALESCE(SUM(cl.difficulty = 'easy'), 0)   AS easy,
                       COALESCE(SUM(cl.difficulty = 'medium'), 0) AS medium,
                       COALESCE(SUM(cl.difficulty = 'hard'), 0)   AS hard
                FROM category c
                LEFT JOIN clue cl ON cl.category_id = c.id
                GROUP BY c.id, c.name
                ORDER BY c.name
                """,
                (rs, i) -> new Category(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("easy"), rs.getInt("medium"), rs.getInt("hard")));
    }

    public List<Clue> findClues(long categoryId, int limit) {
        return jdbc.query("""
                SELECT id, category_id, difficulty, question, answer
                FROM clue
                WHERE category_id = ?
                ORDER BY FIELD(difficulty, 'easy', 'medium', 'hard'), id
                LIMIT ?
                """,
                (rs, i) -> new Clue(rs.getLong("id"), rs.getLong("category_id"), rs.getString("difficulty"),
                        rs.getString("question"), rs.getString("answer")),
                categoryId, limit);
    }

    public long countClues() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM clue", Long.class);
        return count == null ? 0 : count;
    }

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
