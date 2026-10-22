package party.jeoparody.trivia;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings bound from the {@code trivia.*} section of application.yml. */
@ConfigurationProperties(prefix = "trivia")
public record TriviaProperties(
        @DefaultValue("https://opentdb.com") String baseUrl,
        @DefaultValue("false") boolean importOnStartup,
        @DefaultValue("50") int questionsPerCategory,
        @DefaultValue("5500ms") Duration requestDelay,
        List<Integer> categories) {

    public TriviaProperties {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
