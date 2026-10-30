package party.jeoparody.research;

import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the research worker as a separate Cloud Run Job execution, so clue research never slows a game: the
 * job runs the same image with RESEARCH_WORKER=true, works through the queue, and shuts down.
 *
 * Auth: the game server's service account (jeoparody-run) calls the Cloud Run Admin API with its own short-lived
 * token (Application Default Credentials), so no key is involved. It needs permission to run the job
 * (infra/README.md). If the call fails, the work falls back to a background thread here.
 */
public class CloudRunJobLauncher implements ResearchLauncher {

    private static final Logger log = LoggerFactory.getLogger(CloudRunJobLauncher.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final ResearchProperties props;
    private final ResearchLauncher fallback;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Instant lastLaunch = Instant.EPOCH;
    private GoogleCredentials credentials;

    public CloudRunJobLauncher(ResearchProperties props, ResearchLauncher fallback, Clock clock) {
        this.props = props;
        this.fallback = fallback;
        this.clock = clock;
    }

    /** The Cloud Run Admin API URL that starts one execution of the job. */
    String runUrl() {
        return "https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run"
                .formatted(props.project(), props.region(), props.jobName());
    }

    /** True if a launch is allowed now (at most one per launch-interval, so a burst of games starts one job). */
    synchronized boolean claimLaunchSlot() {
        Instant now = clock.instant();
        if (Duration.between(lastLaunch, now).compareTo(props.launchInterval()) < 0) {
            return false;
        }
        lastLaunch = now;
        return true;
    }

    @Override
    public void launch() {
        if (!props.enabled() || !claimLaunchSlot()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(runUrl()))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + accessToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    log.info("Started research job {}", props.jobName());
                    return;
                }
                log.warn("Could not start research job (HTTP {}): {}; researching in the game server instead",
                        response.statusCode(), abbreviate(response.body()));
            } catch (Exception e) {
                log.warn("Could not start research job: {}; researching in the game server instead", e.getMessage());
            }
            fallback.launch();
        });
    }

    private synchronized String accessToken() throws java.io.IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    @Override
    public String describe() {
        return "Cloud Run Job " + props.jobName() + " (" + props.region() + ")";
    }
}
