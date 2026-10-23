package party.jeoparody.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import party.jeoparody.support.TestData;

/**
 * Plays a whole (short) game the way real devices do: REST to create the room, then WebSockets for
 * the host console, the TV board and two player phones. Results must land in MySQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameWebSocketIT {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Screen> screens = new ArrayList<>();

    /** One connected device: collects every message the server pushes. */
    final class Screen implements WebSocket.Listener {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        JsonNode state;
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    inbox.add(MAPPER.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        void send(String action, Map<String, ?> fields) {
            Map<String, Object> msg = new java.util.HashMap<>(fields);
            msg.put("action", action);
            try {
                socket.sendText(MAPPER.writeValueAsString(msg), true).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void send(String action) {
            send(action, Map.of());
        }

        /** Waits for a message of the given type (ignoring state updates). */
        JsonNode awaitMessage(String type) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if ("state".equals(m.path("type").asText())) {
                    state = m.path("state");
                }
                if (type.equals(m.path("type").asText())) {
                    return m;
                }
            }
            throw new AssertionError("timed out waiting for message " + type);
        }

        /** Waits until the latest state matches. Fails fast on an error message. */
        JsonNode awaitState(Predicate<JsonNode> condition) throws InterruptedException {
            if (state != null && condition.test(state)) {
                return state;
            }
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if ("error".equals(m.path("type").asText())) {
                    throw new AssertionError("server error: " + m.path("message").asText());
                }
                if ("state".equals(m.path("type").asText())) {
                    state = m.path("state");
                    if (condition.test(state)) {
                        return state;
                    }
                }
            }
            throw new AssertionError("timed out; last state: " + state);
        }

        JsonNode awaitPhase(String phase) throws InterruptedException {
            return awaitState(s -> phase.equals(s.path("phase").asText()));
        }
    }

    private Screen connect(String query) throws Exception {
        Screen screen = new Screen();
        screen.socket = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?" + query), screen)
                .get(5, TimeUnit.SECONDS);
        screens.add(screen);
        return screen;
    }

    @BeforeEach
    void seed() {
        TestData.reset(jdbc);
        TestData.seedPlayableTrivia(jdbc, 30);
    }

    @AfterEach
    void disconnect() {
        screens.forEach(s -> s.socket.abort());
    }

    private JsonNode createGame() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/games"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode());
        return MAPPER.readTree(res.body());
    }

    @Test
    void wrongHostKeyIsRejected() throws Exception {
        String code = createGame().path("code").asText();
        Screen impostor = connect("game=" + code + "&role=host&key=guess");
        JsonNode error = impostor.awaitMessage("error");
        assertTrue(error.path("fatal").asBoolean());
    }

    @Test
    void unknownRoomCodeIsRejected() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/games/ZZZZ")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode());
    }

    @Test
    void aWholeGameOverWebSockets() throws Exception {
        JsonNode created = createGame();
        String code = created.path("code").asText();
        Screen host = connect("game=" + code + "&role=host&key=" + created.path("hostKey").asText());
        Screen board = connect("game=" + code + "&role=board");
        Screen alice = connect("game=" + code + "&role=player");
        Screen bob = connect("game=" + code + "&role=player");

        // Lobby: two teams join
        alice.send("join", Map.of("name", "Alice"));
        String aliceId = alice.awaitMessage("joined").path("teamId").asText();
        bob.send("join", Map.of("name", "Bob"));
        String bobId = bob.awaitMessage("joined").path("teamId").asText();
        host.awaitState(s -> s.path("teams").size() == 2);

        // Category vote
        host.send("startVoting");
        JsonNode voting = alice.awaitPhase("VOTING");
        List<Long> pool = new ArrayList<>();
        voting.path("pool").forEach(k -> pool.add(k.path("id").asLong()));
        assertEquals(26, pool.size());
        alice.send("vote", Map.of("keywordIds", pool.subList(0, 12)));
        bob.send("vote", Map.of("keywordIds", pool.subList(6, 18)));
        host.awaitState(s -> s.path("teams").get(0).path("voted").asBoolean() && s.path("teams").get(1).path("voted").asBoolean());
        host.send("closeVoting");

        // Final category vote
        JsonNode finalVote = alice.awaitPhase("FINAL_VOTING");
        alice.send("voteFinal", Map.of("keywordId", finalVote.path("finalOptions").get(0).path("id").asLong()));
        host.send("closeFinalVoting");
        JsonNode hostBoard = host.awaitPhase("BOARD");
        assertEquals(6, hostBoard.path("board").path("categories").size());

        // Play one normal clue: Bob buzzes and is right
        int[] cell = firstNormalCell(hostBoard);
        int value = hostBoard.path("board").path("categories").get(cell[0]).path("cells").get(cell[1]).path("value").asInt();
        host.send("selectCell", Map.of("column", cell[0], "row", cell[1]));
        JsonNode boardClue = board.awaitPhase("CLUE");
        assertFalse(boardClue.path("clue").has("response"), "the TV must not show the answer early");
        host.send("openBuzzers");
        bob.awaitState(s -> s.path("clue").path("buzzersOpen").asBoolean());
        bob.send("buzz");
        host.awaitState(s -> bobId.equals(s.path("clue").path("answeringTeamId").asText()));
        host.send("judge", Map.of("correct", true));
        bob.awaitState(s -> s.path("me").path("score").asInt() == value);
        host.send("returnToBoard");
        host.awaitPhase("BOARD");

        // Skip to Final Jeopardy (only Bob has a positive score)
        host.send("endRound");
        host.awaitState(s -> "DOUBLE_JEOPARDY".equals(s.path("round").asText()));
        host.send("endRound");
        JsonNode wager = bob.awaitPhase("FINAL_WAGER");
        assertTrue(wager.path("me").path("finalEligible").asBoolean());
        bob.send("wager", Map.of("amount", value));
        host.send("revealFinalClue");
        bob.awaitPhase("FINAL_ANSWER");
        bob.send("finalAnswer", Map.of("text", "What is the answer?"));
        host.awaitState(s -> s.path("teams").get(1).path("answered").asBoolean());
        host.send("lockFinalAnswers");
        host.awaitPhase("FINAL_REVEAL");
        host.send("judgeFinal", Map.of("teamId", bobId, "correct", true));
        bob.awaitState(s -> s.path("me").path("score").asInt() == value * 2);
        host.send("finish");
        JsonNode over = board.awaitPhase("GAME_OVER");
        assertEquals("Bob", over.path("standings").get(0).path("name").asText());

        // The result is saved to MySQL
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (TestData.count(jdbc, "SELECT COUNT(*) FROM game_result WHERE code = ?", code) == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(value * 2, jdbc.queryForObject("""
                SELECT t.score FROM game_result_team t JOIN game_result r ON r.id = t.game_result_id
                WHERE r.code = ? AND t.place = 1
                """, Integer.class, code));
        assertEquals(0, host.state.path("teams").get(0).path("score").asInt(), "Alice (" + aliceId + ") never scored");
    }

    private static int[] firstNormalCell(JsonNode hostState) {
        JsonNode categories = hostState.path("board").path("categories");
        for (int c = 0; c < categories.size(); c++) {
            JsonNode cells = categories.get(c).path("cells");
            for (int r = 0; r < cells.size(); r++) {
                if (!cells.get(r).path("dailyDouble").asBoolean()) {
                    return new int[] {c, r};
                }
            }
        }
        throw new AssertionError("no normal cell");
    }
}
