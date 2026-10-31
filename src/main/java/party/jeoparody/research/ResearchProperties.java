package party.jeoparody.research;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings bound from {@code trivia.research.*} in application.yml.
 *
 * @param enabled         false in tests: work is queued but never started
 * @param mode            "local" (background thread in this app) or "job" (start the Cloud Run Job)
 * @param worker          true only inside the Cloud Run Job: do the work, then exit
 * @param staleThreshold  a category is stale when every one of its clues has been shown this many times
 * @param cooldown        how long after researching a category before it may be queued again
 * @param fanWikis        allow-listed MediaWiki fan wikis: "Keyword, Other Keyword = https://host/w/api.php"
 * @param blockedHosts    hosts never contacted even if listed above (empty: every listed wiki may be read)
 */
@ConfigurationProperties(prefix = "trivia.research")
public record ResearchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("local") String mode,
        @DefaultValue("false") boolean worker,
        @DefaultValue("jeoparody-research") String jobName,
        @DefaultValue("us-west1") String region,
        @DefaultValue("jeoparody-party") String project,
        @DefaultValue("2") int staleThreshold,
        @DefaultValue("20") int cluesPerTask,
        @DefaultValue("10") int maxTasksPerPlan,
        @DefaultValue("3d") Duration cooldown,
        @DefaultValue("2m") Duration launchInterval,
        @DefaultValue("60000") int maxArticleChars,
        @DefaultValue List<String> fanWikis,
        @DefaultValue List<String> blockedHosts) {

    public boolean jobMode() {
        return "job".equalsIgnoreCase(mode);
    }
}
