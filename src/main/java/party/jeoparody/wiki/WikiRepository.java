package party.jeoparody.wiki;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** The wiki topic queue, plus inserting generated clues. */
@Repository
public class WikiRepository {

    static final int MAX_TOPIC_LENGTH = 200;

    private final JdbcTemplate jdbc;

    public WikiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues a topic. Re-submitting a failed or not-found topic queues it again; done topics are left alone. */
    public void queue(String topic) {
        String t = topic.length() <= MAX_TOPIC_LENGTH ? topic : topic.substring(0, MAX_TOPIC_LENGTH);
        jdbc.update("""
                INSERT INTO wiki_topic (topic, status) VALUES (?, 'queued')
                ON DUPLICATE KEY UPDATE
                    error  = IF(wiki_topic.status IN ('failed', 'not_found'), NULL, wiki_topic.error),
                    status = IF(wiki_topic.status IN ('failed', 'not_found'), 'queued', wiki_topic.status)
                """, t);
    }

    public record QueuedTopic(long id, String topic) {
    }

    public Optional<QueuedTopic> nextQueued() {
        return jdbc.query("SELECT id, topic FROM wiki_topic WHERE status = 'queued' ORDER BY id LIMIT 1",
                (rs, i) -> new QueuedTopic(rs.getLong("id"), rs.getString("topic"))).stream().findFirst();
    }

    public void markDone(long id, WikiPage page, int generated, int added) {
        jdbc.update("""
                UPDATE wiki_topic SET status = 'done', page_title = ?, page_url = ?, clues_generated = ?,
                                      clues_added = ?, error = NULL
                WHERE id = ?
                """, page.title(), page.url(), generated, added, id);
    }

    public void markNotFound(long id) {
        jdbc.update("UPDATE wiki_topic SET status = 'not_found', error = 'No matching Wikipedia article' WHERE id = ?", id);
    }

    public void markFailed(long id, String error) {
        String e = error == null ? "Unknown error" : error;
        jdbc.update("UPDATE wiki_topic SET status = 'failed', error = ? WHERE id = ?",
                e.length() <= 500 ? e : e.substring(0, 500), id);
    }

    public List<WikiTopic> list(int limit) {
        return jdbc.query("""
                SELECT id, topic, status, page_title, page_url, clues_generated, clues_added, error, updated_at
                FROM wiki_topic
                ORDER BY FIELD(status, 'queued', 'failed', 'not_found', 'done'), updated_at DESC, id DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Timestamp updated = rs.getTimestamp("updated_at");
                    return new WikiTopic(rs.getLong("id"), rs.getString("topic"), rs.getString("status"),
                            rs.getString("page_title"), rs.getString("page_url"), rs.getInt("clues_generated"),
                            rs.getInt("clues_added"), rs.getString("error"),
                            updated == null ? null : updated.toInstant());
                },
                limit);
    }

    public int countTopics() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM wiki_topic", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Inserts a generated clue (raw fields only; the caller then saves wording and keywords).
     * Returns empty if an identical clue already exists.
     */
    public OptionalLong insertClue(long categoryId, GeneratedClue c, String sourceUrl) {
        KeyHolder keys = new GeneratedKeyHolder();
        int rows = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT IGNORE INTO clue (category_id, difficulty, question, answer, question_hash, source_url, source_quote)
                    VALUES (?, ?, ?, ?, SHA2(LOWER(TRIM(?)), 256), ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, categoryId);
            ps.setString(2, c.difficulty());
            ps.setString(3, c.clue());
            ps.setString(4, truncate(c.answer(), 512));
            ps.setString(5, c.clue());
            ps.setString(6, truncate(sourceUrl, 500));
            ps.setString(7, c.quote());
            return ps;
        }, keys);
        if (rows != 1 || keys.getKey() == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(keys.getKey().longValue());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
