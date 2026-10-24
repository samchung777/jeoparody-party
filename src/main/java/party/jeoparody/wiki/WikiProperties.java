package party.jeoparody.wiki;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings bound from {@code trivia.wiki.*}. */
@ConfigurationProperties(prefix = "trivia.wiki")
public record WikiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://en.wikipedia.org/w/api.php") String apiUrl,
        @DefaultValue("false") boolean seedOnStartup,
        @DefaultValue("24") int cluesPerTopic,
        @DefaultValue("60000") int maxArticleChars,
        List<String> starterTopics) {

    public WikiProperties {
        starterTopics = starterTopics == null ? List.of() : List.copyOf(starterTopics);
    }
}
