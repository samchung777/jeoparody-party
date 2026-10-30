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
import party.jeoparody.research.SuggestionService;

/**
 * One WebSocket endpoint (/ws) for every screen. Connect with:
 * <ul>
 *   <li>{@code /ws?game=CODE&role=board}</li>
 *   <li>{@code /ws?game=CODE&role=host&key=HOST_KEY}</li>
 *   <li>{@code /ws?game=CODE&role=player[&teamId=..&token=..]} (teamId/token to reconnect)</li>
 * </ul>
 * Clients send {@code {"action": "...", ...}}. After every change the server pushes each screen
 * its own filtered view: {@code {"type": "state", "state": {...}}}.
 *
 * Where it fits: registered at /ws by WebSocketConfig. Browsers connect as wss:// through Cloud Run's front end
 * (which is why application-cloud.yml trusts forwarded headers: otherwise Spring's same-origin check rejects them).
 * Why it matters: it is the only door into a live game. It authenticates the host (host key), routes each action
 * to Game, and after any change pushes every screen its own filtered state, so phones never see answers.
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);
    /**
     * Limits for a slow phone: a send that takes over 5 s, or 4 MB queued, closes that one connection instead of
     * holding up everyone else (drawings make some messages large).
     */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT = 4 * 1024 * 1024;

    /** A connected screen. teamId changes when a player joins. */
    private static final class Connection {
        final WebSocketSession session;
        final String code;
        final Game.Viewer viewer;
        volatile String teamId;

        /**
         * Remembers which game, which kind of screen, and (for phones) which team this socket belongs to.
         */
        Connection(WebSocketSession session, String code, Game.Viewer viewer, String teamId) {
            this.session = session;
            this.code = code;
            this.viewer = viewer;
            this.teamId = teamId;
        }
    }

    private final GameService games;
    private final ClueFeedbackService feedback;
    private final SuggestionService suggestions;
    private final ObjectMapper mapper;
    /**
     * Every open socket, keyed by session id. Concurrent map: many sockets connect and disconnect at once.
     */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /** Spring injects these (constructor injection), which also makes the handler easy to test. */
    public GameSocketHandler(GameService games, ClueFeedbackService feedback, SuggestionService suggestions,
            ObjectMapper mapper) {
        this.games = games;
        this.feedback = feedback;
        this.suggestions = suggestions;
        this.mapper = mapper;
    }

    /**
     * Runs when a screen connects. Reads game, role (host/board/player) and, for the host, the secret key from the
     * URL. A wrong code or host key is refused and closed. A phone with a saved teamId and token is reconnected to its
     * team. The new screen gets the current state straight away.
     */
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

    /**
     * Runs for every message a screen sends: parse the JSON, route {"action": ...} by role, and if the game changed,
     * save results when needed (GameService.afterAction) and broadcast. Rule errors (GameException) go back to the
     * sender only, as {"type":"error"}; the socket stays open.
     */
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

    /**
     * Every host-only action, mapped to a Game method. Returns true when the game state changed (so screens need an
     * update); search returns false because only the host sees its results.
     */
    private boolean hostAction(Game game, String action, JsonNode msg, Connection c) {
        switch (action) {
            case "ping" -> { return false; }
            case "search" -> {
                // The host types in a Final Jeopardy category: results go to the host screen only.
                String query = msg.path("query").asText("");
                send(c.session, Map.of("type", "searchResults", "query", query, "results", game.searchKeywords(query)));
                return false;
            }
            case "browse" -> {
                // Genre browsing for the Final category: results go to the host screen only.
                String genre = msg.path("genre").asText("");
                send(c.session, Map.of("type", "browseResults", "genre", genre, "results", game.browseGenre(genre)));
                return false;
            }
            case "suggest" -> {
                suggest(game, c, msg, "host", "Host");
                return false;
            }
            case "setTheme" -> game.setTheme(msg.path("theme").asText(""));
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

    /**
     * Every phone action. A phone must join (and get its teamId) before anything else. A buzz only triggers a
     * broadcast when it won: losing buzzes change nothing.
     */
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
        if (action.equals("browse")) {
            // Genre browsing: only this phone gets the list.
            String genre = msg.path("genre").asText("");
            send(c.session, Map.of("type", "browseResults", "genre", genre, "results", game.browseGenre(genre)));
            return false;
        }
        if (action.equals("suggest")) {
            String name = c.teamId == null ? "Player" : game.teams().stream().filter(t -> t.id().equals(c.teamId))
                    .map(Team::name).findFirst().orElse("Player");
            suggest(game, c, msg, c.teamId == null ? c.session.getId() : c.teamId, name);
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

    /**
     * A screen disconnected (closed tab, lost signal). The team stays in the game; the phone can rejoin.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    /** Network-level errors are normal on phones; logged quietly, the close handler cleans up. */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error: {}", exception.getMessage());
    }

    /**
     * Saves a topic suggestion (cleaned and rate-limited by SuggestionService) and confirms it to the sender only.
     * It is researched when the game ends.
     */
    private void suggest(Game game, Connection c, JsonNode msg, String who, String name) {
        String topic = suggestions.submit(msg.path("topic").asText(""), game.code(), game.gameNumber(), who, name);
        send(c.session, Map.of("type", "suggested", "topic", topic));
    }

    /** Pushes every connected screen of this game its own view of the new state. */
    private void broadcast(Game game) {
        for (Connection c : connections.values()) {
            if (c.code.equals(game.code())) {
                sendState(c, game);
            }
        }
    }

    /** Sends one screen its view: Game.view filters by role (host, board or this team). */
    private void sendState(Connection c, Game game) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "state");
        envelope.put("state", game.view(c.viewer, c.teamId));
        send(c.session, envelope);
    }

    /** Serializes to JSON and sends; a failure to one screen never breaks the action for others. */
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

    /** Reads ?game=...&role=...&key=... from the WebSocket URL. */
    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        if (uri != null) {
            UriComponentsBuilder.fromUri(uri).build().getQueryParams()
                    .forEach((k, v) -> params.put(k, v.isEmpty() ? null : decode(v.get(0))));
        }
        return params;
    }

    /** URL-decodes a query value (team tokens and keys are URL-safe, but be safe). */
    private static String decode(String s) {
        return s == null ? null : java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Number of open connections (for tests/diagnostics). */
    int connectionCount() {
        return connections.size();
    }

    /** Room codes with at least one open screen (diagnostics and tests). */
    Set<String> connectedCodes() {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        connections.values().forEach(c -> codes.add(c.code));
        return codes;
    }
}
