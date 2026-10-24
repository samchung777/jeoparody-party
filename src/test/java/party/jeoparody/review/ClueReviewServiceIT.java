package party.jeoparody.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import party.jeoparody.feedback.ClueFeedbackService;
import party.jeoparody.support.IntegrationTest;
import party.jeoparody.support.TestData;

class ClueReviewServiceIT extends IntegrationTest {

    @MockitoBean
    ClueReviewer reviewer;
    @Autowired
    ClueReviewService service;
    @Autowired
    ClueFeedbackService feedback;

    @Test
    void behindTheScenesCluesAreRetiredLoggedAndRestorable() throws Exception {
        long category = TestData.category(jdbc, "Film");
        long director = TestData.playableClue(jdbc, category, "easy", "Who directed Jaws?", "Steven Spielberg", "Jaws");
        long character = TestData.playableClue(jdbc, category, "easy", "Who captains the Orca?", "Quint", "Jaws");
        long unconverted = TestData.rawClue(jdbc, category, "easy", "Not converted yet?", "Later");
        when(reviewer.offTopic(anyList())).thenReturn(Map.of(director, "asks for the director"));

        assertEquals(2, service.status().pending(), "only converted, live clues are reviewed");
        service.processQueue();

        assertNotNull(jdbc.queryForObject("SELECT retired_at FROM clue WHERE id = ?", Object.class, director));
        assertNull(jdbc.queryForObject("SELECT retired_at FROM clue WHERE id = ?", Object.class, character));
        Map<String, Object> log = jdbc.queryForMap("SELECT action, reason, note FROM clue_feedback WHERE clue_id = ?", director);
        assertEquals("remove", log.get("action"));
        assertEquals(ClueReviewRepository.REASON, log.get("reason"));
        assertEquals("asks for the director", log.get("note"));

        assertEquals(0, service.status().pending(), "reviewed clues aren't reviewed again");
        assertEquals(1, service.status().retired());
        assertEquals(0, TestData.count(jdbc, "SELECT review_version FROM clue WHERE id = ?", unconverted));

        feedback.restore(director);
        assertNull(jdbc.queryForObject("SELECT retired_at FROM clue WHERE id = ?", Object.class, director));
        assertEquals(0, service.status().retired());
    }

    @Test
    void aFailingBatchIsRetriedLaterNotMarkedReviewed() throws Exception {
        long category = TestData.category(jdbc, "Film");
        TestData.playableClue(jdbc, category, "easy", "Q?", "A", "Film");
        when(reviewer.offTopic(anyList())).thenThrow(new java.io.IOException("boom"));
        Thread worker = new Thread(service::processQueue);
        worker.start();
        Thread.sleep(500);
        worker.interrupt(); // stop it during the back-off
        worker.join(5000);
        assertEquals(1, service.status().pending());
        assertEquals(List.of(), jdbc.queryForList("SELECT id FROM clue_feedback", Long.class));
    }
}
