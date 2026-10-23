package party.jeoparody.feedback;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import party.jeoparody.enrich.EnrichmentService;

/**
 * Applies host feedback on clues:
 * <ul>
 *   <li><b>remove</b> – the clue is retired: excluded from games, keyword counts and the library,
 *       but kept so a re-import can't bring it back and so it can be restored.</li>
 *   <li><b>reword</b> – the clue goes back through Gemini with the host's note as guidance.</li>
 * </ul>
 * Every action is logged in {@code clue_feedback}.
 */
@Service
public class ClueFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(ClueFeedbackService.class);

    public static final Set<String> REASONS = Set.of("wrong", "unclear", "obscure", "giveaway", "offensive", "other");
    static final int MAX_NOTE = 300;

    /** A clue that was retired or sent for rewording, for the library's review list. */
    public record FlaggedClue(long feedbackId, long clueId, String action, String reason, String note,
            String gameCode, Instant createdAt, String clue, String response, String question, String answer,
            boolean retired) {
    }

    private final JdbcTemplate jdbc;
    private final EnrichmentService enrichment;

    public ClueFeedbackService(JdbcTemplate jdbc, EnrichmentService enrichment) {
        this.jdbc = jdbc;
        this.enrichment = enrichment;
    }

    @Transactional
    public void submit(long clueId, String action, String reason, String note, String gameCode) {
        String cleanReason = REASONS.contains(reason) ? reason : "other";
        String cleanNote = note == null ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            cleanNote = cleanNote.substring(0, MAX_NOTE);
        }
        if (cleanNote != null && cleanNote.isEmpty()) {
            cleanNote = null;
        }
        int updated = switch (action) {
            case "remove" -> jdbc.update("UPDATE clue SET retired_at = CURRENT_TIMESTAMP WHERE id = ?", clueId);
            case "reword" -> jdbc.update("""
                    UPDATE clue SET enrich_version = 0, enrich_attempts = 0, feedback_note = ?
                    WHERE id = ? AND retired_at IS NULL
                    """, rewordHint(cleanReason, cleanNote), clueId);
            default -> throw new IllegalArgumentException("Unknown feedback action: " + action);
        };
        if (updated == 0) {
            throw new IllegalArgumentException("Clue " + clueId + " not found");
        }
        jdbc.update("INSERT INTO clue_feedback (clue_id, game_code, action, reason, note) VALUES (?, ?, ?, ?, ?)",
                clueId, gameCode, action, cleanReason, cleanNote);
        log.info("Clue {} flagged: {} ({}){}", clueId, action, cleanReason, cleanNote == null ? "" : " - " + cleanNote);
        if (action.equals("reword")) {
            enrichment.requestRun();
        }
    }

    @Transactional
    public void restore(long clueId) {
        if (jdbc.update("UPDATE clue SET retired_at = NULL WHERE id = ?", clueId) == 0) {
            throw new IllegalArgumentException("Clue " + clueId + " not found");
        }
        jdbc.update("INSERT INTO clue_feedback (clue_id, action, reason) VALUES (?, 'restore', 'other')", clueId);
    }

    /** Most recent remove/reword feedback, newest first. */
    public List<FlaggedClue> recent(int limit) {
        return jdbc.query("""
                SELECT f.id, f.clue_id, f.action, f.reason, f.note, f.game_code, f.created_at,
                       cl.jeopardy_clue, cl.jeopardy_response, cl.question, cl.answer, cl.retired_at
                FROM clue_feedback f
                JOIN clue cl ON cl.id = f.clue_id
                WHERE f.action IN ('remove', 'reword')
                ORDER BY f.created_at DESC, f.id DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Timestamp created = rs.getTimestamp("created_at");
                    return new FlaggedClue(rs.getLong("id"), rs.getLong("clue_id"), rs.getString("action"),
                            rs.getString("reason"), rs.getString("note"), rs.getString("game_code"),
                            created == null ? null : created.toInstant(), rs.getString("jeopardy_clue"),
                            rs.getString("jeopardy_response"), rs.getString("question"), rs.getString("answer"),
                            rs.getTimestamp("retired_at") != null);
                },
                limit);
    }

    private static String rewordHint(String reason, String note) {
        String why = switch (reason) {
            case "wrong" -> "The response may be wrong or outdated.";
            case "unclear" -> "The clue was confusing or badly worded.";
            case "obscure" -> "The clue was too obscure; make it more approachable.";
            case "giveaway" -> "The clue gave away the answer.";
            case "offensive" -> "The clue was inappropriate; keep it family friendly.";
            default -> "The host didn't like this clue.";
        };
        return note == null ? why : why + " Host note: " + note;
    }
}
