package party.jeoparody.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the real app and a real MySQL (see application-test.yml).
 * Flyway migrates the test database on startup; every test starts with empty tables.
 *
 * <p>RANDOM_PORT starts a real embedded Tomcat: the WebSocket config needs a real servlet container
 * (the default MOCK environment has none). It also means every integration test, including
 * GameWebSocketIT, shares one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        TestData.reset(jdbc);
    }
}
