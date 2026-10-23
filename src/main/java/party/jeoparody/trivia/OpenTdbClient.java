package party.jeoparody.trivia;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Thin HTTP client for the Open Trivia DB API. */
@Component
public class OpenTdbClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public OpenTdbClient(ObjectMapper mapper, TriviaProperties props) {
        this.mapper = mapper;
        this.baseUrl = props.baseUrl();
    }

    public Map<Integer, String> fetchCategories() throws IOException, InterruptedException {
        return OpenTdbParser.parseCategories(get("/api_category.php"));
    }

    /**
     * Requests a session token. With a token, Open Trivia DB never returns the same question twice,
     * which lets us page through a whole category. Returns null if a token can't be obtained.
     */
    public String requestToken() throws IOException, InterruptedException {
        JsonNode root = get("/api_token.php?command=request");
        return OpenTdbParser.responseCode(root) == OpenTdbParser.SUCCESS ? root.path("token").asText(null) : null;
    }

    public JsonNode fetchQuestions(int categoryId, int amount, String token) throws IOException, InterruptedException {
        String path = "/api.php?amount=" + amount + "&category=" + categoryId + "&type=multiple&encode=url3986";
        return get(token == null ? path : path + "&token=" + token);
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "jeoparody-party")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            return mapper.createObjectNode().put("response_code", OpenTdbParser.RATE_LIMITED);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Open Trivia DB returned HTTP " + response.statusCode() + " for " + path);
        }
        return mapper.readTree(response.body());
    }
}
