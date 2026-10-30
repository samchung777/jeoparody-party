package party.jeoparody.research;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.review.ClueReviewService;

/**
 * Picks where research runs: trivia.research.mode=job (Cloud Run, set in application-cloud.yml) starts the
 * separate Cloud Run Job; anything else (local development) uses a background thread in this app.
 */
@Configuration
public class ResearchConfig {

    @Bean
    public ResearchLauncher researchLauncher(ResearchProperties props, ResearchService research,
            EnrichmentService enrichment, ClueReviewService review) {
        LocalResearchLauncher local = new LocalResearchLauncher(research, enrichment, review, props.enabled());
        return props.jobMode() ? new CloudRunJobLauncher(props, local, Clock.systemUTC()) : local;
    }
}
