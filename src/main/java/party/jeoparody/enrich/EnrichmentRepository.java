package party.jeoparody.enrich;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC access for enrichment: pending clues, saving results and keyword lookups. */
@Repository
public class EnrichmentRepository {

    private final JdbcTemplate jdbc;

    public EnrichmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClueToEnrich> findPending(int limit, int maxAttempts) {
        return jdbc.query("""
                SELECT cl.id, c.name AS category, cl.difficulty, cl.question, cl.answer, cl.feedback_note
                FROM clue cl
                JOIN category c ON c.id = cl.category_id
                WHERE cl.enrich_version < ? AND cl.enrich_attempts < ? AND cl.retired_at IS NULL
                ORDER BY cl.enriched_at IS NOT NULL, cl.id
                LIMIT ?
                """,
                (rs, i) -> new ClueToEnrich(rs.getLong("id"), rs.getString("category"), rs.getString("difficulty"),
                        rs.getString("question"), rs.getString("answer"), rs.getString("feedback_note")),
                EnrichmentPrompt.VERSION, maxAttempts, limit);
    }

    public long countPending(int maxAttempts) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clue WHERE enrich_version < ? AND enrich_attempts < ? AND retired_at IS NULL",
                Long.class,
                EnrichmentPrompt.VERSION, maxAttempts);
        return n == null ? 0 : n;
    }

    /** Counts a failed conversion, so a clue the model keeps failing on is eventually skipped. */
    public void recordAttempt(List<ClueToEnrich> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE clue SET enrich_attempts = enrich_attempts + 1 WHERE id = ?",
                batch.stream().map(c -> new Object[] {c.id()}).toList());
    }

    @Transactional
    public void save(EnrichedClue e) {
        jdbc.update("""
                UPDATE clue SET jeopardy_clue = ?, jeopardy_response = ?, enriched_at = CURRENT_TIMESTAMP,
                                enrich_version = ?, enrich_attempts = 0, feedback_note = NULL
                WHERE id = ?
                """, e.clue(), truncate(e.response(), 600), EnrichmentPrompt.VERSION, e.id());
        jdbc.update("DELETE FROM clue_keyword WHERE clue_id = ?", e.id());
        for (String name : e.keywords()) {
            jdbc.update("INSERT IGNORE INTO keyword (name) VALUES (?)", name);
            Long keywordId = jdbc.queryForObject("SELECT id FROM keyword WHERE name = ?", Long.class, name);
            jdbc.update("INSERT IGNORE INTO clue_keyword (clue_id, keyword_id) VALUES (?, ?)", e.id(), keywordId);
        }
    }

    /** Most-used keywords, fed back into the prompt so Gemini reuses them. */
    public List<String> topKeywords(int limit) {
        return jdbc.queryForList("""
                SELECT k.name
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id AND cl.retired_at IS NULL
                WHERE k.retired_at IS NULL
                GROUP BY k.id, k.name
                ORDER BY COUNT(*) DESC, k.name
                LIMIT ?
                """, String.class, limit);
    }

    /** Removes keywords no clue uses any more (e.g. after re-tagging with a new prompt). */
    public int deleteUnusedKeywords() {
        return jdbc.update("""
                DELETE k FROM keyword k
                LEFT JOIN clue_keyword ck ON ck.keyword_id = k.id
                WHERE ck.keyword_id IS NULL
                """);
    }

    /** Keywords with enough clues for a board column that don't have a category title yet. */
    public List<KeywordToTitle> findUntitledKeywords(int minClues, int limit) {
        return jdbc.query("""
                SELECT k.id, k.name,
                       SUBSTRING_INDEX(GROUP_CONCAT(cl.jeopardy_response ORDER BY RAND() SEPARATOR '\n'), '\n', 4)
                           AS samples
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id AND cl.retired_at IS NULL
                WHERE k.title IS NULL AND k.retired_at IS NULL
                GROUP BY k.id, k.name
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                LIMIT ?
                """,
                (rs, i) -> new KeywordToTitle(rs.getLong("id"), rs.getString("name"),
                        rs.getString("samples") == null ? List.of() : List.of(rs.getString("samples").split("\n"))),
                minClues, limit);
    }

    public void saveTitle(long keywordId, String title) {
        jdbc.update("UPDATE keyword SET title = ? WHERE id = ?", truncate(title, 80), keywordId);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
