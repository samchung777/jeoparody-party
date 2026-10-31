package party.jeoparody.enrich;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * Minimal Gemini client for the Vertex AI REST API that returns structured JSON.
 * Authenticates with Application Default Credentials: your gcloud login locally,
 * the Cloud Run service account in production. No API keys are stored anywhere.
 *
 * Where it fits: the one place that talks to Gemini. GeminiClueEnricher, GeminiCategoryTitler, GeminiClueReviewer
 * and GeminiWikiClueGenerator all build a prompt and call generateJson here.
 * Why it matters: it shows keyless authentication at runtime (ADC as the jeoparody-run service account on Cloud
 * Run, the same idea as OIDC in the pipeline), and it marks 429/5xx errors as retryable so callers back off.
 */
@Component
public class GeminiClient {

    /**
     * OAuth scope requested for the access token: general Google Cloud access, limited in practice by IAM roles.
     */
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** Java's built-in HTTP client (no extra library); 10 s to connect, 120 s per Gemini reply. */
    /** Writing 24 clues from a 60,000-character article can take a few minutes. */
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final EnrichmentProperties props;
    private GoogleCredentials credentials;

    /**
     * Credentials are loaded lazily on first use, so tests and apps with Gemini turned off never need Google credentials.
     */
    public GeminiClient(ObjectMapper mapper, EnrichmentProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** Shared Jackson ObjectMapper (JSON), so prompt builders and parsers use the same settings. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /** Sends the prompt and returns the model's reply parsed as JSON matching {@code responseSchema}. */
    public JsonNode generateJson(String prompt, ObjectNode responseSchema) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject().put("role", "user");
        content.putArray("parts").addObject().put("text", prompt);
        ObjectNode config = body.putObject("generationConfig");
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", responseSchema);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // Long articles can take Gemini minutes; a timeout is retried with back-off like a busy server.
            throw new TransientEnrichmentException("Gemini took longer than " + REQUEST_TIMEOUT.toSeconds()
                    + " s (will retry)");
        }
        int code = response.statusCode();
        if (code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
            throw new TransientEnrichmentException("Vertex AI returned HTTP " + code + " (will retry)");
        }
        if (code != 200) {
            throw new IOException("Vertex AI returned HTTP " + code + ": " + abbreviate(response.body()));
        }
        return mapper.readTree(extractText(mapper.readTree(response.body())));
    }

    /** Joins the non-"thought" text parts of the first candidate. */
    static String extractText(JsonNode reply) throws IOException {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : reply.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) {
                text.append(part.path("text").asText(""));
            }
        }
        if (text.isEmpty()) {
            throw new IOException("Gemini returned no text: " + abbreviate(reply.toString()));
        }
        return text.toString();
    }

    /**
     * Vertex AI's generateContent URL for our project, location (global) and model (trivia.enrichment.model).
     */
    private String endpoint() {
        String host = "global".equals(props.location())
                ? "aiplatform.googleapis.com"
                : props.location() + "-aiplatform.googleapis.com";
        return "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, props.project(), props.location(), props.model());
    }

    /**
     * Application Default Credentials: locally your `gcloud auth application-default login`; on Cloud Run the
     * service account's token from the metadata server. Refreshed automatically before it expires.
     */
    private synchronized String accessToken() throws IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    /** Keeps error messages and logs short. */
    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
