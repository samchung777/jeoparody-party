package party.jeoparody.game;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import party.jeoparody.feedback.ClueFeedbackService;

/**
 * One WebSocket endpoint (/ws) for every screen. Connect with:
 * <ul>
 *   <li>{@code /ws?game=CODE&role=board}</li>
 *   <li>{@code /ws?game=CODE&role=host&key=HOST_KEY}</li>
 *   <li>{@code /ws?game=CODE&role=player[&teamId=..&token=..]} (teamId/token to reconnect)</li>
 * </ul>
 * Clients send {@code {"action": "...", ...}}. After every change the server pushes each screen
 * its own filtered view: {@code {"type": "state", "state": {...}}}.
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT = 4 * 1024 * 1024;

    /** A connected screen. teamId changes when a player joins. */
    private static final class Connection {
        final WebSocketSession session;
        final String code;
        final Game.Viewer viewer;
        volatile String teamId;

        Connection(WebSocketSession session, String code, Game.Viewer viewer, String teamId) {
            this.session = session;
            this.code = code;
            this.viewer = viewer;
            this.teamId = teamId;
        }
    }

    private final GameService games;
    private final ClueFeedbackService feedback;
    private final ObjectMapper mapper;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public GameSocketHandler(GameService games, ClueFeedbackService feedback, ObjectMapper mapper) {
        this.games = games;
        this.feedback = feedback;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        Map<String, String> params = queryParams(raw.getUri());
        Game game = games.find(params.get("game")).orElse(null);
        if (game == null) {
            send(session, Map.of("type", "error", "fatal", true, "message", "No game with that code"));
            raw.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String role = params.getOrDefault("role", "player");
        Game.Viewer viewer;
        String teamId = null;
        switch (role) {
            case "host" -> {
                if (!game.isHostKey(params.get("key"))) {
                    send(session, Map.of("type", "error", "fatal", true, "message", "This host link isn't valid"));
                    raw.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                viewer = Game.Viewer.HOST;
            }
            case "board" -> viewer = Game.Viewer.BOARD;
            default -> {
                viewer = Game.Viewer.PLAYER;
                if (params.containsKey("teamId")) {
                    try {
                        teamId = game.rejoin(params.get("teamId"), params.get("token")).id();
                    } catch (GameException e) {
                        send(session, Map.of("type", "rejoinFailed", "message", e.getMessage()));
                    }
                }
            }
        }
        Connection connection = new Connection(session, game.code(), viewer, teamId);
        connections.put(raw.getId(), connection);
        sendState(connection, game);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Connection c = connections.get(raw.getId());
        if (c == null) {
            return;
        }
        Game game = games.find(c.code).orElse(null);
        if (game == null) {
            send(c.session, Map.of("type", "error", "fatal", true, "message", "This game has ended"));
            return;
        }
        JsonNode msg;
        try {
            msg = mapper.readTree(message.getPayload());
        } catch (IOException e) {
            send(c.session, Map.of("type", "error", "message", "Malformed message"));
            return;
        }
        String action = msg.path("action").asText("");
        try {
            boolean changed = switch (c.viewer) {
                case HOST -> hostAction(game, action, msg, c);
                case PLAYER -> playerAction(game, c, action, msg);
                case BOARD -> false;
            };
            if (changed) {
                games.afterAction(game);
                broadcast(game);
            }
        } catch (GameException | IllegalArgumentException e) {
            send(c.session, Map.of("type", "error", "message", Objects.toString(e.getMessage(), "Invalid request")));
        } catch (RuntimeException e) {
            // e.g. the database is unreachable: tell the screen instead of dropping its connection
            log.warn("Action '{}' failed in game {}", action, c.code, e);
            send(c.session, Map.of("type", "error", "message", "Something went wrong – try again"));
        }
    }

    private boolean hostAction(Game game, String action, JsonNode msg, Connection c) {
        switch (action) {
            case "ping" -> { return false; }
            case "search" -> {
                // The host types in a Final Jeopardy category: results go to the host screen only.
                String query = msg.path("query").asText("");
                send(c.session, Map.of("type", "searchResults", "query", query, "results", game.searchKeywords(query)));
                return false;
            }
            case "setFinalCategory" -> game.closeFinalVoting(games.clueSource(), msg.path("keywordId").asLong(-1));
            case "removeCategory" -> {
                long keywordId = msg.path("keywordId").asLong(-1);
                game.recordCategoryRemoval(keywordId); // validates the category is in this game
                try {
                    feedback.removeKeyword(keywordId, msg.path("note").asText(null), game.code());
                } catch (RuntimeException e) {
                    log.warn("Could not remove category {}: {}", keywordId, e.getMessage());
                    throw new GameException("Couldn't remove that category – try again");
                }
            }
            case "startVoting" -> game.startVoting(games.clueSource());
            case "closeVoting" -> game.closeVoting(games.clueSource());
            case "closeFinalVoting" -> game.closeFinalVoting(games.clueSource());
            case "selectCell" -> game.selectCell(msg.path("column").asInt(-1), msg.path("row").asInt(-1));
            case "openBuzzers" -> game.openBuzzers();
            case "judge" -> game.judge(msg.path("correct").asBoolean());
            case "reveal" -> game.reveal();
            case "returnToBoard" -> game.returnToBoard();
            case "endRound" -> game.endRound();
            case "setControl" -> game.setControl(msg.path("teamId").asText(null));
            case "adjustScore" -> game.adjustScore(msg.path("teamId").asText(null), msg.path("delta").asInt());
            case "wager" -> game.wager(msg.path("teamId").asText(null), msg.path("amount").asInt(-1));
            case "revealFinalClue" -> game.revealFinalClue();
            case "lockFinalAnswers" -> game.lockFinalAnswers();
            case "spotlight" -> game.spotlight(msg.path("teamId").asText(null));
            case "judgeFinal" -> game.judgeFinal(msg.path("teamId").asText(null), msg.path("correct").asBoolean());
            case "removeTeam" -> game.removeTeam(msg.path("teamId").asText(null));
            case "finish" -> game.finish();
            case "playAgain" -> game.playAgain(games.clueSource());
            case "clueFeedback" -> {
                long clueId = msg.path("clueId").asLong(-1);
                String what = msg.path("feedbackAction").asText("");
                game.recordFeedback(clueId, what); // validates the clue is in this game
                try {
                    feedback.submit(clueId, what, msg.path("reason").asText("other"), msg.path("note").asText(null),
                            game.code());
                } catch (RuntimeException e) {
                    log.warn("Could not save feedback for clue {}: {}", clueId, e.getMessage());
                    throw new GameException("Couldn't save that feedback – try again");
                }
            }
            default -> throw new GameException("Unknown action: " + action);
        }
        return true;
    }

    private boolean playerAction(Game game, Connection c, String action, JsonNode msg) {
        if (action.equals("ping")) {
            return false;
        }
        if (action.equals("search")) {
            // Only the searching phone gets the results; nothing changes for anyone else.
            String query = msg.path("query").asText("");
            send(c.session, Map.of("type", "searchResults", "query", query, "results", game.searchKeywords(query)));
            return false;
        }
        if (action.equals("join")) {
            Team team = game.join(msg.path("name").asText(""), msg.path("drawing").asText(null));
            c.teamId = team.id();
            send(c.session, Map.of("type", "joined", "teamId", team.id(), "token", team.token()));
            return true;
        }
        if (c.teamId == null) {
            throw new GameException("Join the game first");
        }
        switch (action) {
            case "vote" -> {
                List<Long> ids = new ArrayList<>();
                msg.path("keywordIds").forEach(n -> ids.add(n.asLong()));
                game.vote(c.teamId, ids);
            }
            case "voteFinal" -> game.voteFinal(c.teamId, msg.path("keywordId").asLong(-1));
            case "buzz" -> {
                return game.buzz(c.teamId); // only broadcast when this buzz won
            }
            case "wager" -> game.wager(c.teamId, msg.path("amount").asInt(-1));
            case "finalAnswer" -> game.submitFinalAnswer(c.teamId, msg.path("drawing").asText(null),
                    msg.path("text").asText(""));
            default -> throw new GameException("Unknown action: " + action);
        }
        return true;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error: {}", exception.getMessage());
    }

    /** Pushes every connected screen of this game its own view of the new state. */
    private void broadcast(Game game) {
        for (Connection c : connections.values()) {
            if (c.code.equals(game.code())) {
                sendState(c, game);
            }
        }
    }

    private void sendState(Connection c, Game game) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "state");
        envelope.put("state", game.view(c.viewer, c.teamId));
        send(c.session, envelope);
    }

    private void send(WebSocketSession session, Map<String, ?> payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not send to {}: {}", session.getId(), e.getMessage());
        }
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        if (uri != null) {
            UriComponentsBuilder.fromUri(uri).build().getQueryParams()
                    .forEach((k, v) -> params.put(k, v.isEmpty() ? null : decode(v.get(0))));
        }
        return params;
    }

    private static String decode(String s) {
        return s == null ? null : java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Number of open connections (for tests/diagnostics). */
    int connectionCount() {
        return connections.size();
    }

    Set<String> connectedCodes() {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        connections.values().forEach(c -> codes.add(c.code));
        return codes;
    }
}
