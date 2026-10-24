package party.jeoparody.review;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClueReviewRepository {

    /** Shown in the library's Flagged clues list; restorable like any host removal. */
    public static final String REASON = "behind-the-scenes";

    private final JdbcTemplate jdbc;

    public ClueReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClueToReview> findPending(int limit) {
        return jdbc.query("""
                SELECT cl.id, c.name AS category, cl.jeopardy_clue, cl.jeopardy_response
                FROM clue cl
                JOIN category c ON c.id = cl.category_id
                WHERE cl.retired_at IS NULL AND cl.jeopardy_clue IS NOT NULL AND cl.review_version < ?
                ORDER BY cl.id
                LIMIT ?
                """,
                (rs, i) -> new ClueToReview(rs.getLong("id"), rs.getString("category"), rs.getString("jeopardy_clue"),
                        rs.getString("jeopardy_response")),
                ReviewPrompt.VERSION, limit);
    }

    public long countPending() {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM clue
                WHERE retired_at IS NULL AND jeopardy_clue IS NOT NULL AND review_version < ?
                """, Long.class, ReviewPrompt.VERSION);
        return n == null ? 0 : n;
    }

    public long countRetired() {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT f.clue_id) FROM clue_feedback f JOIN clue cl ON cl.id = f.clue_id
                WHERE f.reason = ? AND cl.retired_at IS NOT NULL
                """, Long.class, REASON);
        return n == null ? 0 : n;
    }

    /** Retires the clue and logs why, exactly like a host's "Remove" (so it can be restored). */
    @Transactional
    public boolean retire(long clueId, String why) {
        if (jdbc.update("UPDATE clue SET retired_at = CURRENT_TIMESTAMP WHERE id = ? AND retired_at IS NULL", clueId) == 0) {
            return false;
        }
        jdbc.update("INSERT INTO clue_feedback (clue_id, action, reason, note) VALUES (?, 'remove', ?, ?)",
                clueId, REASON, why);
        return true;
    }

    public void markReviewed(List<ClueToReview> batch) {
        jdbc.batchUpdate("UPDATE clue SET review_version = ? WHERE id = ?",
                batch.stream().map(c -> new Object[] {ReviewPrompt.VERSION, c.id()}).toList());
    }
}
