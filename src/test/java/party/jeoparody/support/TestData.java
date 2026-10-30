package party.jeoparody.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

import party.jeoparody.enrich.EnrichmentPrompt;

/** Builds known database states for integration tests. */
public final class TestData {

    private static final AtomicInteger SOURCE_IDS = new AtomicInteger(1000);
    private static final List<String> TABLES = List.of(
            "game_result_team", "game_result", "clue_feedback", "keyword_genre", "research_task", "topic_suggestion",
            "clue_keyword", "keyword", "clue", "category", "wiki_topic");

    private TestData() {
    }

    /** Empties every table (children first). Flyway's schema history is left alone. */
    public static void reset(JdbcTemplate jdbc) {
        TABLES.forEach(t -> jdbc.update("DELETE FROM " + t));
    }

    public static long category(JdbcTemplate jdbc, String name) {
        int sourceId = SOURCE_IDS.incrementAndGet();
        jdbc.update("INSERT INTO category (source, source_id, name) VALUES ('test', ?, ?)", sourceId, name);
        return jdbc.queryForObject("SELECT id FROM category WHERE source = 'test' AND source_id = ?", Long.class,
                sourceId);
    }

    /** A raw clue, as the importer stores it (not yet converted by Gemini). */
    public static long rawClue(JdbcTemplate jdbc, long categoryId, String difficulty, String question, String answer) {
        jdbc.update("""
                INSERT INTO clue (category_id, difficulty, question, answer, question_hash)
                VALUES (?, ?, ?, ?, SHA2(LOWER(TRIM(?)), 256))
                """, categoryId, difficulty, question, answer, question);
        return jdbc.queryForObject("SELECT id FROM clue WHERE question = ?", Long.class, question);
    }

    /** A clue already converted to Jeopardy form and tagged with keywords. */
    public static long playableClue(JdbcTemplate jdbc, long categoryId, String difficulty, String question,
            String answer, String... keywords) {
        long id = rawClue(jdbc, categoryId, difficulty, question, answer);
        jdbc.update("""
                UPDATE clue SET jeopardy_clue = ?, jeopardy_response = ?, enriched_at = CURRENT_TIMESTAMP,
                                enrich_version = ?
                WHERE id = ?
                """, "This is the clue for: " + question, "What is " + answer + "?", EnrichmentPrompt.VERSION, id);
        for (String keyword : keywords) {
            jdbc.update("INSERT IGNORE INTO keyword (name, title) VALUES (?, ?)", keyword, "Punny " + keyword);
            Long keywordId = jdbc.queryForObject("SELECT id FROM keyword WHERE name = ?", Long.class, keyword);
            jdbc.update("INSERT INTO clue_keyword (clue_id, keyword_id) VALUES (?, ?)", id, keywordId);
        }
        return id;
    }

    /** Enough trivia for a full game: {@code keywords} keywords with 7 clues each (2 easy, 2 medium, 3 hard). */
    public static void seedPlayableTrivia(JdbcTemplate jdbc, int keywords) {
        long category = category(jdbc, "Test Category");
        List<String> difficulties = List.of("easy", "easy", "medium", "medium", "hard", "hard", "hard");
        for (int k = 1; k <= keywords; k++) {
            for (int i = 0; i < difficulties.size(); i++) {
                playableClue(jdbc, category, difficulties.get(i), "Question " + k + "-" + i + "?", "Answer " + k + "-" + i,
                        "Theme " + k);
            }
        }
    }

    public static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
