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
 *
 * Where it fits: called from the host screen during a game (GameSocketHandler: clueFeedback, removeCategory) and
 * from the library page (FeedbackController). Why it matters: it is how play-testing improves the content: bad
 * clues leave future games without being deleted, so they stay restorable and can't be re-imported.
 */
@Service
public class ClueFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(ClueFeedbackService.class);

    /**
     * Allowed reasons (anything else is stored as 'other'); each maps to guidance for Gemini in rewordHint.
     */
    public static final Set<String> REASONS = Set.of("wrong", "unclear", "obscure", "giveaway", "offensive", "other");
    static final int MAX_NOTE = 300;

    /** A clue that was retired or sent for rewording, for the library's review list. */
    public record FlaggedClue(long feedbackId, long clueId, String action, String reason, String note,
            String gameCode, Instant createdAt, String clue, String response, String question, String answer,
            boolean retired) {
    }

    private final JdbcTemplate jdbc;
    private final EnrichmentService enrichment;

    /** Needs EnrichmentService to send 'reword' clues back through Gemini. */
    public ClueFeedbackService(JdbcTemplate jdbc, EnrichmentService enrichment) {
        this.jdbc = jdbc;
        this.enrichment = enrichment;
    }

    /**
     * Applies one host decision in a single transaction: 'remove' retires the clue; 'reword' resets its enrichment
     * version so the next Gemini pass rewrites it, guided by the reason and note. Then logs it in clue_feedback.
     */
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

    /** Undo for a removed clue (from the library page); also logged. */
    @Transactional
    public void restore(long clueId) {
        if (jdbc.update("UPDATE clue SET retired_at = NULL WHERE id = ?", clueId) == 0) {
            throw new IllegalArgumentException("Clue " + clueId + " not found");
        }
        jdbc.update("INSERT INTO clue_feedback (clue_id, action, reason) VALUES (?, 'restore', 'other')", clueId);
    }

    /** A category (keyword) a host removed from future games. */
    public record RemovedCategory(long id, String name, String title, String note, Instant removedAt, int clueCount) {
    }

    /**
     * Removes a whole category from future games: the keyword is retired (hidden from the vote pool, search
     * and boards). Its clues are kept, since most also belong to other keywords.
     */
    @Transactional
    public void removeKeyword(long keywordId, String note, String gameCode) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            cleanNote = cleanNote.substring(0, MAX_NOTE);
        }
        String stored = gameCode == null ? cleanNote : "game " + gameCode + (cleanNote == null ? "" : ": " + cleanNote);
        int updated = jdbc.update("""
                UPDATE keyword SET retired_at = COALESCE(retired_at, CURRENT_TIMESTAMP), retired_note = ?
                WHERE id = ?
                """, stored, keywordId);
        if (updated == 0) {
            throw new IllegalArgumentException("Category " + keywordId + " not found");
        }
        log.info("Category {} removed{}", keywordId, stored == null ? "" : " (" + stored + ")");
    }

    /** Undo for a removed category: it can appear in votes and boards again. */
    public void restoreKeyword(long keywordId) {
        if (jdbc.update("UPDATE keyword SET retired_at = NULL, retired_note = NULL WHERE id = ?", keywordId) == 0) {
            throw new IllegalArgumentException("Category " + keywordId + " not found");
        }
    }

    /** Removed categories, newest first, with how many clues they hold (library page). */
    public List<RemovedCategory> removedKeywords() {
        return jdbc.query("""
                SELECT k.id, k.name, k.title, k.retired_note, k.retired_at, COUNT(ck.clue_id) AS clue_count
                FROM keyword k LEFT JOIN clue_keyword ck ON ck.keyword_id = k.id
                WHERE k.retired_at IS NOT NULL
                GROUP BY k.id, k.name, k.title, k.retired_note, k.retired_at
                ORDER BY k.retired_at DESC
                """,
                (rs, i) -> new RemovedCategory(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                        rs.getString("retired_note"), rs.getTimestamp("retired_at").toInstant(), rs.getInt("clue_count")));
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

    /** Turns the host's reason and note into an instruction Gemini reads on the next rewrite. */
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
