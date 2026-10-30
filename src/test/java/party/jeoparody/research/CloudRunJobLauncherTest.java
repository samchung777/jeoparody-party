package party.jeoparody.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit test (Surefire, `*Test`): the Cloud Run Job launcher's API URL and its launch throttle. */
class CloudRunJobLauncherTest {

    private static ResearchProperties props() {
        return new ResearchProperties(true, "job", false, "jeoparody-research", "us-west1", "jeoparody-party", 2, 20,
                10, Duration.ofDays(3), Duration.ofMinutes(2), 60000, List.of(), List.of("fandom.com"));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T20:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void callsTheCloudRunAdminApiForTheJob() {
        CloudRunJobLauncher launcher = new CloudRunJobLauncher(props(), null, Clock.systemUTC());
        assertEquals("https://run.googleapis.com/v2/projects/jeoparody-party/locations/us-west1/jobs/jeoparody-research:run",
                launcher.runUrl());
        assertTrue(launcher.describe().contains("jeoparody-research"));
    }

    @Test
    void aBurstOfFinishedGamesStartsOneExecution() {
        MutableClock clock = new MutableClock();
        CloudRunJobLauncher launcher = new CloudRunJobLauncher(props(), null, clock);
        assertTrue(launcher.claimLaunchSlot());
        clock.now = clock.now.plusSeconds(30);
        assertFalse(launcher.claimLaunchSlot(), "within 2 minutes of the last launch");
        clock.now = clock.now.plusSeconds(120);
        assertTrue(launcher.claimLaunchSlot());
    }
}
