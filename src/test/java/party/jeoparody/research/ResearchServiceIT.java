package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;
import party.jeoparody.wiki.GeneratedClue;
import party.jeoparody.wiki.WikiClueGenerator;
import party.jeoparody.wiki.WikiPage;
import party.jeoparody.wiki.WikiSource;
import party.jeoparody.wiki.WikiText;

/**
 * Integration test (Failsafe, `*IT`): the research queue in real MySQL, with Wikipedia, the fan wiki and Gemini
 * replaced by Mockito fakes. Covers source choice, storing clues under the right keyword, statuses, and the
 * planner's rules (threshold, cooldown, duplicate suggestions).
 */
class ResearchServiceIT extends IntegrationTest {

    @MockitoBean
    WikiSource wikipedia;
    @MockitoBean
    FanWikiClient fanWiki;
    @MockitoBean
    WikiClueGenerator generator;
    @Autowired
    ResearchService research;
    @Autowired
    ResearchPlanner planner;
    @Autowired
    ResearchRepository repository;

    private final WikiPage fanPage = new WikiPage(7, "Theme 1 Hero (fan wiki)", "https://w.example/wiki/Hero", "fan text");
    private final WikiPage wikiPage = new WikiPage(42, "Theme 1", "https://en.wikipedia.org/wiki/Theme_1", "wiki text");

    private static List<GeneratedClue> clues(String prefix, int n) {
        List<GeneratedClue> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new GeneratedClue("medium", prefix + " clue " + i, "Who is " + prefix + i + "?", prefix + i,
                    "quote " + i, List.of("Theme 1")));
        }
        return out;
    }

    private void makeStale(String keyword) {
        jdbc.update("UPDATE clue cl JOIN clue_keyword ck ON ck.clue_id = cl.id JOIN keyword k ON k.id = ck.keyword_id"
                + " SET cl.staleness = 2 WHERE k.name = ?", keyword);
    }

    private Map<String, Object> task() {
        return jdbc.queryForMap("SELECT status, sources, source_url, clues_added, error FROM research_task");
    }

    @Test
    void aStaleCategoryIsRefreshedFromItsFanWiki() throws Exception {
        TestData.seedPlayableTrivia(jdbc, 2);
        makeStale("Theme 1");
        when(fanWiki.fetch("Theme 1")).thenReturn(Optional.of(fanPage));
        when(generator.generate(any(), eq(fanPage), anyInt(), anySet())).thenReturn(clues("Fan", 6));

        assertEquals(1, planner.plan("ABCD").staleCategories(), "only Theme 1 has every clue shown twice");
        assertEquals(1, research.processQueue());

        Map<String, Object> t = task();
        assertEquals("done", t.get("status"));
        assertEquals("fanwiki", t.get("sources"));
        assertEquals(6, ((Number) t.get("clues_added")).intValue());
        verify(wikipedia, never()).fetch(anyString());
        verify(generator).generate(any(), eq(fanPage), eq(20),
                eq(Set.of(WikiText.answerKey("Answer 1-0"), WikiText.answerKey("Answer 1-1"),
                        WikiText.answerKey("Answer 1-2"), WikiText.answerKey("Answer 1-3"),
                        WikiText.answerKey("Answer 1-4"), WikiText.answerKey("Answer 1-5"),
                        WikiText.answerKey("Answer 1-6"))));
        assertEquals(13, TestData.count(jdbc, """
                SELECT COUNT(*) FROM clue cl JOIN clue_keyword ck ON ck.clue_id = cl.id
                JOIN keyword k ON k.id = ck.keyword_id WHERE k.name = 'Theme 1'"""), "7 old + 6 new");
        assertEquals(6, TestData.count(jdbc,
                "SELECT COUNT(*) FROM clue WHERE source_url = 'https://w.example/wiki/Hero' AND staleness = 0"),
                "new clues start fresh and keep their source for attribution");
    }

    @Test
    void withoutAFanWikiAStaleCategoryFallsBackToWikipedia() throws Exception {
        TestData.seedPlayableTrivia(jdbc, 1);
        makeStale("Theme 1");
        when(fanWiki.fetch(anyString())).thenReturn(Optional.empty());
        when(wikipedia.fetch("Theme 1")).thenReturn(Optional.of(wikiPage));
        when(generator.generate(any(), eq(wikiPage), anyInt(), anySet())).thenReturn(clues("Wiki", 3));
        planner.plan(null);
        research.processQueue();
        assertEquals("wikipedia", task().get("sources"));
        assertEquals(3, ((Number) task().get("clues_added")).intValue());
    }

    @Test
    void suggestionsUseWikipediaPlusTheFanWikiAndReportBack() throws Exception {
        repository.suggest("Theme 1", "ABCD", "Red");
        repository.suggest("theme 1", "ABCD", "Blue"); // same topic again
        when(fanWiki.fetch("Theme 1")).thenReturn(Optional.of(fanPage));
        when(wikipedia.fetch("Theme 1")).thenReturn(Optional.of(wikiPage));
        when(generator.generate(any(), any(), anyInt(), anySet())).thenReturn(clues("Both", 5));

        ResearchPlanner.Plan plan = planner.plan("ABCD");
        assertEquals(1, plan.suggestions());
        research.processQueue();
        Map<String, Object> t = task();
        assertEquals("wikipedia, fanwiki", t.get("sources"));
        assertEquals("https://en.wikipedia.org/wiki/Theme_1", t.get("source_url"), "Wikipedia stays the source of record");
        assertEquals("done", jdbc.queryForObject("SELECT status FROM topic_suggestion WHERE suggested_by = 'Red'", String.class));

        planner.plan("ABCD"); // the duplicate is seen once the first is handled
        assertEquals("rejected", jdbc.queryForObject("SELECT status FROM topic_suggestion WHERE suggested_by = 'Blue'", String.class));
    }

    @Test
    void notFoundAndFailuresAreRecorded() throws Exception {
        repository.queue(ResearchTask.MANUAL, null, "Nothing Anywhere", null);
        when(fanWiki.fetch(anyString())).thenReturn(Optional.empty());
        when(wikipedia.fetch(anyString())).thenReturn(Optional.empty());
        research.processQueue();
        assertEquals("not_found", task().get("status"));

        jdbc.update("DELETE FROM research_task");
        repository.queue(ResearchTask.MANUAL, null, "Theme 1", null);
        when(wikipedia.fetch("Theme 1")).thenReturn(Optional.of(wikiPage));
        when(generator.generate(any(), any(), anyInt(), anySet())).thenThrow(new IOException("Vertex AI returned HTTP 403"));
        research.processQueue();
        assertEquals("failed", task().get("status"));
        assertTrue(((String) task().get("error")).contains("403"));
    }

    @Test
    void aTaskIsClaimedOnlyOnce() {
        repository.queue(ResearchTask.MANUAL, null, "Theme 1", null);
        assertTrue(repository.claimNext().isPresent());
        assertTrue(repository.claimNext().isEmpty(), "a second worker finds nothing to do");
    }

    @Test
    void thePlannerRespectsTheThresholdAndTheCooldown() {
        TestData.seedPlayableTrivia(jdbc, 2);
        jdbc.update("UPDATE clue SET staleness = 1");
        assertEquals(0, planner.plan(null).staleCategories(), "shown once is not stale yet");
        makeStale("Theme 2");
        assertEquals(1, planner.plan(null).staleCategories());
        assertEquals(0, planner.plan(null).staleCategories(), "already queued");
        jdbc.update("UPDATE research_task SET status = 'done'");
        assertEquals(0, planner.plan(null).staleCategories(), "researched within the cooldown");
        jdbc.update("UPDATE research_task SET updated_at = NOW() - INTERVAL 4 DAY");
        assertEquals(1, planner.plan(null).staleCategories(), "after the cooldown it can be refreshed again");
    }
}
