package party.jeoparody.research;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import party.jeoparody.wiki.WikiText;

/** SQL for research tasks and topic suggestions (V9 migration). */
@Repository
public class ResearchRepository {

    private final JdbcTemplate jdbc;

    public ResearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ tasks

    public long queue(String kind, Long keywordId, String topic, String gameCode) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO research_task (kind, keyword_id, topic, game_code) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, kind);
            ps.setObject(2, keywordId);
            ps.setString(3, truncate(topic, 200));
            ps.setString(4, gameCode);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** A stale category: a keyword whose every live clue has been shown at least the threshold number of times. */
    public record StaleCategory(long keywordId, String name, int clues, int minStaleness) {
    }

    /**
     * Live keywords with a full column of clues where even the freshest clue has been shown {@code threshold}
     * times, and that aren't already queued, running, or researched within the cooldown.
     */
    public List<StaleCategory> staleCategories(int threshold, Duration cooldown, int limit) {
        return jdbc.query("""
                SELECT k.id, k.name, COUNT(*) AS clues, MIN(cl.staleness) AS freshest
                FROM keyword k
                JOIN clue_keyword ck ON ck.keyword_id = k.id
                JOIN clue cl ON cl.id = ck.clue_id
                WHERE k.retired_at IS NULL AND cl.retired_at IS NULL AND cl.jeopardy_clue IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM research_task t
                      WHERE t.keyword_id = k.id
                        AND (t.status IN ('queued', 'running') OR t.updated_at > ?))
                GROUP BY k.id, k.name
                HAVING COUNT(*) >= 5 AND MIN(cl.staleness) >= ?
                ORDER BY MIN(cl.staleness) DESC, COUNT(*) DESC
                LIMIT ?
                """,
                (rs, i) -> new StaleCategory(rs.getLong("id"), rs.getString("name"), rs.getInt("clues"),
                        rs.getInt("freshest")),
                Timestamp.from(java.time.Instant.now().minus(cooldown)), threshold, limit);
    }

    /**
     * Claims the oldest queued task by flipping it to 'running' only if it is still queued, so two workers
     * running at once never take the same task.
     */
    public Optional<ResearchTask> claimNext() {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM research_task WHERE status = 'queued' ORDER BY id LIMIT 1", Long.class);
            if (ids.isEmpty()) {
                return Optional.empty();
            }
            if (jdbc.update("UPDATE research_task SET status = 'running' WHERE id = ? AND status = 'queued'",
                    ids.get(0)) == 1) {
                return find(ids.get(0));
            }
        }
        return Optional.empty();
    }

    /** Puts tasks left 'running' by a crashed worker back in the queue. */
    public int requeueStuck(Duration olderThan) {
        return jdbc.update("UPDATE research_task SET status = 'queued' WHERE status = 'running' AND updated_at < ?",
                Timestamp.from(java.time.Instant.now().minus(olderThan)));
    }

    public void finish(long id, String status, String sources, String sourceUrl, int cluesAdded, String error) {
        jdbc.update("""
                UPDATE research_task SET status = ?, sources = ?, source_url = ?, clues_added = ?, error = ?
                WHERE id = ?
                """, status, truncate(sources, 200), truncate(sourceUrl, 500), cluesAdded, truncate(error, 500), id);
        jdbc.update("UPDATE topic_suggestion SET status = ? WHERE research_task_id = ?",
                "done".equals(status) ? "done" : "not_found".equals(status) ? "not_found" : "queued", id);
    }

    public int countQueued() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM research_task WHERE status = 'queued'", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<ResearchTask> find(long id) {
        return jdbc.query("SELECT * FROM research_task WHERE id = ?", (rs, i) -> task(rs), id).stream().findFirst();
    }

    public List<ResearchTask> recentTasks(int limit) {
        return jdbc.query("""
                SELECT * FROM research_task
                ORDER BY FIELD(status, 'running', 'queued', 'failed', 'not_found', 'done'), updated_at DESC, id DESC
                LIMIT ?
                """, (rs, i) -> task(rs), limit);
    }

    private static ResearchTask task(ResultSet rs) throws SQLException {
        long keyword = rs.getLong("keyword_id");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ResearchTask(rs.getLong("id"), rs.getString("kind"), rs.wasNull() ? null : keyword,
                rs.getString("topic"), rs.getString("status"), rs.getString("sources"), rs.getString("source_url"),
                rs.getInt("clues_added"), rs.getString("error"), rs.getString("game_code"),
                updated == null ? null : updated.toInstant());
    }

    /** Answer keys of the keyword's live clues, so new clues don't repeat answers it already has. */
    public Set<String> existingAnswers(String keywordName) {
        Set<String> keys = new HashSet<>();
        jdbc.queryForList("""
                SELECT cl.answer FROM clue cl
                JOIN clue_keyword ck ON ck.clue_id = cl.id
                JOIN keyword k ON k.id = ck.keyword_id
                WHERE k.name = ? AND cl.retired_at IS NULL
                """, String.class, keywordName).forEach(a -> keys.add(WikiText.answerKey(a)));
        keys.remove("");
        return keys;
    }

    /** The Wikipedia topic line this keyword came from ("Naruto [Shounen Anime]"), if any. */
    public Optional<String> wikiTopicFor(String keywordName) {
        return jdbc.queryForList("SELECT topic FROM wiki_topic WHERE status = 'done' ORDER BY id", String.class)
                .stream()
                .filter(t -> WikiText.parseTopic(t).keyword().equalsIgnoreCase(keywordName))
                .findFirst();
    }

    // ------------------------------------------------------------------ suggestions

    public long suggest(String topic, String gameCode, String suggestedBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO topic_suggestion (topic, game_code, suggested_by) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, truncate(topic, 120));
            ps.setString(2, gameCode);
            ps.setString(3, truncate(suggestedBy, 64));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** New suggestions, oldest first. */
    public List<TopicSuggestion> newSuggestions(int limit) {
        return jdbc.query("SELECT * FROM topic_suggestion WHERE status = 'new' ORDER BY id LIMIT ?",
                (rs, i) -> suggestion(rs), limit);
    }

    /** True if the same topic (ignoring case) was already suggested and is queued or done. */
    public boolean alreadyHandled(String topic) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM topic_suggestion
                WHERE LOWER(topic) = LOWER(?) AND status IN ('queued', 'done')
                """, Integer.class, topic);
        return n != null && n > 0;
    }

    @Transactional
    public void linkSuggestion(long suggestionId, String status, Long taskId) {
        jdbc.update("UPDATE topic_suggestion SET status = ?, research_task_id = ? WHERE id = ?", status, taskId,
                suggestionId);
    }

    public List<TopicSuggestion> recentSuggestions(int limit) {
        return jdbc.query("SELECT * FROM topic_suggestion ORDER BY id DESC LIMIT ?", (rs, i) -> suggestion(rs), limit);
    }

    private static TopicSuggestion suggestion(ResultSet rs) throws SQLException {
        long task = rs.getLong("research_task_id");
        boolean noTask = rs.wasNull();
        Timestamp created = rs.getTimestamp("created_at");
        return new TopicSuggestion(rs.getLong("id"), rs.getString("topic"), rs.getString("game_code"),
                rs.getString("suggested_by"), rs.getString("status"), noTask ? null : task,
                created == null ? null : created.toInstant());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
