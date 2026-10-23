package party.jeoparody.enrich;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings bound from {@code trivia.enrichment.*}. */
@ConfigurationProperties(prefix = "trivia.enrichment")
public record EnrichmentProperties(
        @DefaultValue("false") boolean enabled,
        String project,
        @DefaultValue("global") String location,
        @DefaultValue("gemini-3.5-flash") String model,
        @DefaultValue("20") int batchSize,
        @DefaultValue("3") int maxAttempts) {
}
