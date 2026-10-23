package party.jeoparody.enrich;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * Calls Gemini through the Vertex AI REST API.
 * Authenticates with Application Default Credentials: your gcloud login locally,
 * the Cloud Run service account in production. No API keys are stored anywhere.
 */
@Component
public class GeminiClueEnricher implements ClueEnricher {

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final EnrichmentProperties props;
    private GoogleCredentials credentials;

    public GeminiClueEnricher(ObjectMapper mapper, EnrichmentProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public List<EnrichedClue> enrich(List<ClueToEnrich> batch, List<String> existingKeywords)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject().put("role", "user");
        content.putArray("parts").addObject().put("text", EnrichmentPrompt.build(mapper, batch, existingKeywords));
        ObjectNode config = body.putObject("generationConfig");
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", EnrichmentPrompt.responseSchema(mapper));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
            throw new TransientEnrichmentException("Vertex AI returned HTTP " + code + " (will retry)");
        }
        if (code != 200) {
            throw new IOException("Vertex AI returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return EnrichmentPrompt.parse(mapper.readTree(extractText(mapper.readTree(response.body()))), batch);
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

    private String endpoint() {
        String host = "global".equals(props.location())
                ? "aiplatform.googleapis.com"
                : props.location() + "-aiplatform.googleapis.com";
        return "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, props.project(), props.location(), props.model());
    }

    private synchronized String accessToken() throws IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
