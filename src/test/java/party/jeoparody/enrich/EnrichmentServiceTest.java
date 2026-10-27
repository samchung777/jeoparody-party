package party.jeoparody.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit test (Surefire, `*Test`): no database or network, runs in milliseconds in the CircleCI test job.
 * Checks the exponential back-off used after Gemini rate limits (429).
 */
class EnrichmentServiceTest {

    @Test
    void backOffDoublesUpToFiveMinutes() {
        assertEquals(15_000, EnrichmentService.backoffMillis(1));
        assertEquals(30_000, EnrichmentService.backoffMillis(2));
        assertEquals(60_000, EnrichmentService.backoffMillis(3));
        assertEquals(120_000, EnrichmentService.backoffMillis(4));
        assertEquals(240_000, EnrichmentService.backoffMillis(5));
        assertEquals(300_000, EnrichmentService.backoffMillis(6));
        assertEquals(300_000, EnrichmentService.backoffMillis(50));
    }
}
