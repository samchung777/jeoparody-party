package party.jeoparody;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point: `java -jar jeoparody-party.jar` (the Docker ENTRYPOINT) runs main(), which starts Spring Boot,
 * embedded Tomcat on $PORT, Flyway migrations, and every @Component in the party.jeoparody packages.
 * @SpringBootApplication = auto-configuration + component scanning from this package down.
 * @ConfigurationPropertiesScan = binds the trivia.* settings in application.yml to the *Properties classes.
 * @EnableScheduling = turns on @Scheduled jobs (GameService removes idle rooms every 30 minutes).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JeopardyApplication {

    /**
     * Starts the app; Spring reads application.yml plus any active profile (cloud on Cloud Run, test in tests).
     */
    public static void main(String[] args) {
        SpringApplication.run(JeopardyApplication.class, args);
    }
}
