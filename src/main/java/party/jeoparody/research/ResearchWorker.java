package party.jeoparody.research;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.genre.GenreService;
import party.jeoparody.review.ClueReviewService;
import party.jeoparody.wiki.WikiClueService;

/**
 * The research worker: the same app image started as a Cloud Run Job with RESEARCH_WORKER=true. It runs every
 * content job once, in order, then exits so the job execution ends (and stops costing anything):
 * <ol>
 *   <li>genre rules, 2. research tasks (stale categories, suggestions), 3. queued Wikipedia topics,
 *   4. Gemini enrichment, titles and genres, 5. the behind-the-scenes review.</li>
 * </ol>
 * A MySQL advisory lock (GET_LOCK) makes sure two executions started close together don't do the same work.
 */
@Component
@ConditionalOnProperty(name = "trivia.research.worker", havingValue = "true")
public class ResearchWorker {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorker.class);
    static final String LOCK = "jeoparody-research";

    private final JdbcTemplate jdbc;
    private final GenreService genres;
    private final ResearchService research;
    private final WikiClueService wiki;
    private final EnrichmentService enrichment;
    private final ClueReviewService review;
    private final ApplicationContext context;

    public ResearchWorker(JdbcTemplate jdbc, GenreService genres, ResearchService research, WikiClueService wiki,
            EnrichmentService enrichment, ClueReviewService review, ApplicationContext context) {
        this.jdbc = jdbc;
        this.genres = genres;
        this.research = research;
        this.wiki = wiki;
        this.enrichment = enrichment;
        this.review = review;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAndExit() {
        int code = 0;
        try {
            Boolean ran = jdbc.execute((ConnectionCallback<Boolean>) con -> {
                // The lock belongs to this connection, so the whole run happens while we hold it.
                if (!lock(con, "SELECT GET_LOCK(?, 0)")) {
                    return false;
                }
                try {
                    runOnce();
                } finally {
                    lock(con, "SELECT RELEASE_LOCK(?)");
                }
                return true;
            });
            if (!Boolean.TRUE.equals(ran)) {
                log.info("Another research worker is already running; nothing to do");
            }
        } catch (RuntimeException e) {
            log.error("Research worker failed", e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** Every content job, once, on this thread. */
    void runOnce() {
        genres.assignByRules();
        int tasks = research.processQueue();
        wiki.processQueue();
        enrichment.runNow();
        review.processQueue();
        log.info("Research worker finished: {} research tasks", tasks);
    }

    private static boolean lock(java.sql.Connection con, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, LOCK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }
}
