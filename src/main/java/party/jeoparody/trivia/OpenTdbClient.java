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

    public JsonNode fetchQuestions(int categoryId, int amount) throws IOException, InterruptedException {
        return get("/api.php?amount=" + amount + "&category=" + categoryId + "&type=multiple&encode=url3986");
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
