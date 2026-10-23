package party.jeoparody.game;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One game room and all of its rules. Pure Java (no Spring, no I/O) so every rule is unit-testable.
 * All public methods are synchronized: the host, board and every player device call in concurrently.
 */
public final class Game {

    public static final int ROWS = 5;
    public static final int COLUMNS = 6;
    public static final int BOARD_CATEGORIES = COLUMNS * 2;
    public static final int POOL_SIZE = 26;
    public static final int MIN_CLUES_PER_KEYWORD = ROWS;
    public static final int MIN_DAILY_DOUBLE_WAGER = 5;
    public static final int MAX_TEAMS = 12;
    static final int MAX_NAME_LENGTH = 24;
    static final int MAX_DRAWING_LENGTH = 300_000;
    static final int MAX_TEXT_LENGTH = 120;
    private static final String PNG_PREFIX = "data:image/png;base64,";

    /** What kind of screen is asking for the game state. */
    public enum Viewer { HOST, BOARD, PLAYER }

    record FinalAnswer(String drawing, String text) {
    }

    private final String code;
    private final String hostKey;
    private final BoardBuilder builder;
    private final Supplier<String> tokens;
    private final Clock clock;
    private final Instant createdAt;
    private Instant lastActivity;

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private Phase phase = Phase.LOBBY;

    // Voting
    private List<KeywordOption> pool = List.of();
    private final Map<String, Set<Long>> ballots = new HashMap<>();
    private List<KeywordOption> finalOptions = List.of();
    private final Map<String, Long> finalBallots = new HashMap<>();

    // Boards
    private Board jeopardyBoard;
    private Board doubleJeopardyBoard;
    private Board currentBoard;
    private final Set<Long> usedClueIds = new HashSet<>();
    private String controlTeamId;

    // The clue currently on screen
    private BoardCell currentCell;
    private boolean buzzersOpen;
    private String answeringTeamId;
    private final Set<String> lockedOut = new LinkedHashSet<>();
    private boolean revealed;
    private String dailyDoubleTeamId;
    private Integer dailyDoubleWager;
    private String lastResult;

    // Final Jeopardy
    private KeywordOption finalCategory;
    private CandidateClue finalClue;
    private List<String> finalEligible = List.of();
    private final Map<String, Integer> finalWagers = new HashMap<>();
    private final Map<String, FinalAnswer> finalAnswers = new HashMap<>();
    private final Map<String, Boolean> finalJudged = new HashMap<>();
    private List<String> revealOrder = List.of();
    private String spotlightTeamId;

    public Game(String code, String hostKey, BoardBuilder builder, Supplier<String> tokens, Clock clock) {
        this.code = code;
        this.hostKey = hostKey;
        this.builder = builder;
        this.tokens = tokens;
        this.clock = clock;
        this.createdAt = clock.instant();
        this.lastActivity = createdAt;
    }

    public String code() { return code; }
    public Instant createdAt() { return createdAt; }
    public synchronized Instant lastActivity() { return lastActivity; }
    public synchronized Phase phase() { return phase; }

    public boolean isHostKey(String key) {
        return key != null && java.security.MessageDigest.isEqual(hostKey.getBytes(), key.getBytes());
    }

    public synchronized List<Team> teams() {
        return List.copyOf(teams.values());
    }

    // ------------------------------------------------------------------ Teams

    public synchronized Team join(String name, String drawing) {
        touch();
        if (phase.compareTo(Phase.FINAL_WAGER) >= 0) {
            throw new GameException("This game is already in Final Jeopardy");
        }
        if (teams.size() >= MAX_TEAMS) {
            throw new GameException("This game is full");
        }
        String cleanName = clean(name, MAX_NAME_LENGTH);
        if (cleanName.isEmpty()) {
            cleanName = "Team " + (teams.size() + 1);
        }
        Team team = new Team(tokens.get(), tokens.get(), cleanName, cleanDrawing(drawing));
        teams.put(team.id(), team);
        return team;
    }

    public synchronized Team rejoin(String teamId, String token) {
        Team team = teams.get(teamId);
        if (team == null || token == null || !team.token().equals(token)) {
            throw new GameException("Your team isn't in this game any more. Join again.");
        }
        return team;
    }

    public synchronized void removeTeam(String teamId) {
        touch();
        team(teamId);
        teams.remove(teamId);
        ballots.remove(teamId);
        finalBallots.remove(teamId);
        lockedOut.remove(teamId);
        if (teamId.equals(controlTeamId)) {
            controlTeamId = teams.isEmpty() ? null : teams.keySet().iterator().next();
        }
        if (teamId.equals(answeringTeamId)) {
            answeringTeamId = null;
        }
        finalEligible = finalEligible.stream().filter(id -> !id.equals(teamId)).toList();
        revealOrder = revealOrder.stream().filter(id -> !id.equals(teamId)).toList();
    }

    public synchronized void adjustScore(String teamId, int delta) {
        touch();
        team(teamId).addScore(delta);
    }

    public synchronized void setControl(String teamId) {
        touch();
        team(teamId);
        controlTeamId = teamId;
        if (phase == Phase.DAILY_DOUBLE_WAGER) {
            dailyDoubleTeamId = teamId;
        }
    }

    // ------------------------------------------------------------------ Voting

    public synchronized void startVoting(ClueSource source) {
        touch();
        require(Phase.LOBBY);
        if (teams.isEmpty()) {
            throw new GameException("Wait for at least one team to join");
        }
        List<KeywordOption> eligible = source.eligibleKeywords(MIN_CLUES_PER_KEYWORD);
        if (eligible.size() < BOARD_CATEGORIES + 1) {
            throw new GameException("Only " + eligible.size() + " keywords have enough clues; need "
                    + (BOARD_CATEGORIES + 1) + ". Import more trivia first.");
        }
        pool = builder.pickPool(eligible, POOL_SIZE);
        phase = Phase.VOTING;
    }

    public synchronized void vote(String teamId, Collection<Long> keywordIds) {
        touch();
        require(Phase.VOTING);
        team(teamId);
        Set<Long> ballot = new LinkedHashSet<>(keywordIds);
        if (ballot.size() > BOARD_CATEGORIES) {
            throw new GameException("Pick at most " + BOARD_CATEGORIES + " keywords");
        }
        Set<Long> poolIds = new HashSet<>();
        pool.forEach(k -> poolIds.add(k.id()));
        if (!poolIds.containsAll(ballot)) {
            throw new GameException("That keyword isn't in this game's pool");
        }
        ballots.put(teamId, ballot);
    }

    /** Tallies the board vote, builds both boards and moves on to the Final Jeopardy vote. */
    public synchronized void closeVoting(ClueSource source) {
        touch();
        require(Phase.VOTING);
        Map<Long, KeywordOption> byId = new HashMap<>();
        pool.forEach(k -> byId.put(k.id(), k));

        List<BoardCategory> jeopardy = new ArrayList<>();
        List<BoardCategory> doubleJeopardy = new ArrayList<>();
        Set<Long> chosen = new HashSet<>();
        for (Long id : builder.rank(ballots.values(), pool)) {
            if (jeopardy.size() + doubleJeopardy.size() == BOARD_CATEGORIES) {
                break;
            }
            // Alternate by rank so both rounds get popular categories: #1 J, #2 DJ, #3 J...
            boolean toJeopardy = jeopardy.size() <= doubleJeopardy.size();
            List<BoardCategory> target = toJeopardy ? jeopardy : doubleJeopardy;
            Round round = toJeopardy ? Round.JEOPARDY : Round.DOUBLE_JEOPARDY;
            Optional<BoardCategory> category = builder.buildCategory(byId.get(id), source.cluesForKeyword(id),
                    usedClueIds, round, target.size());
            if (category.isPresent()) {
                target.add(category.get());
                chosen.add(id);
            }
        }
        if (jeopardy.size() < COLUMNS || doubleJeopardy.size() < COLUMNS) {
            throw new GameException("Not enough unused clues to fill both boards. Import more trivia.");
        }
        jeopardyBoard = new Board(Round.JEOPARDY, List.copyOf(jeopardy));
        doubleJeopardyBoard = new Board(Round.DOUBLE_JEOPARDY, List.copyOf(doubleJeopardy));
        builder.placeDailyDoubles(jeopardyBoard);
        builder.placeDailyDoubles(doubleJeopardyBoard);

        finalOptions = pool.stream().filter(k -> !chosen.contains(k.id())).toList();
        phase = Phase.FINAL_VOTING;
    }

    public synchronized void voteFinal(String teamId, long keywordId) {
        touch();
        require(Phase.FINAL_VOTING);
        team(teamId);
        if (finalOptions.stream().noneMatch(k -> k.id() == keywordId)) {
            throw new GameException("That keyword isn't a Final Jeopardy option");
        }
        finalBallots.put(teamId, keywordId);
    }

    /** Picks the most-voted Final category and starts the Jeopardy round. */
    public synchronized void closeFinalVoting(ClueSource source) {
        touch();
        require(Phase.FINAL_VOTING);
        List<List<Long>> votes = finalBallots.values().stream().map(List::of).toList();
        for (Long id : builder.rank(votes, finalOptions)) {
            Optional<CandidateClue> clue = builder.pickFinalClue(source.cluesForKeyword(id), usedClueIds);
            if (clue.isPresent()) {
                finalCategory = finalOptions.stream().filter(k -> k.id() == id).findFirst().orElseThrow();
                finalClue = clue.get();
                usedClueIds.add(finalClue.id());
                break;
            }
        }
        if (finalClue == null) {
            throw new GameException("No unused clue available for Final Jeopardy");
        }
        currentBoard = jeopardyBoard;
        if (controlTeamId == null && !teams.isEmpty()) {
            List<String> ids = new ArrayList<>(teams.keySet());
            controlTeamId = ids.get(Math.floorMod(code.hashCode(), ids.size()));
        }
        phase = Phase.BOARD;
    }

    // ------------------------------------------------------------------ Board play

    public synchronized void selectCell(int column, int row) {
        touch();
        require(Phase.BOARD);
        BoardCell cell = currentBoard.cell(column, row);
        if (cell.used()) {
            throw new GameException("That clue has already been played");
        }
        resetClueState();
        currentCell = cell;
        if (cell.dailyDouble()) {
            if (controlTeamId == null) {
                throw new GameException("Choose which team has control first");
            }
            dailyDoubleTeamId = controlTeamId;
            phase = Phase.DAILY_DOUBLE_WAGER;
        } else {
            phase = Phase.CLUE;
        }
    }

    /** Daily Double: between $5 and the larger of the team's score or the round's top value. */
    public synchronized int dailyDoubleMaxWager(String teamId) {
        return Math.max(team(teamId).score(), currentBoard.round().maxValue());
    }

    /** Wager from a player device (or entered by the host on the team's behalf). */
    public synchronized void wager(String teamId, int amount) {
        touch();
        Team team = team(teamId);
        if (phase == Phase.DAILY_DOUBLE_WAGER) {
            if (!teamId.equals(dailyDoubleTeamId)) {
                throw new GameException("Only the team in control wagers on a Daily Double");
            }
            int max = dailyDoubleMaxWager(teamId);
            if (amount < MIN_DAILY_DOUBLE_WAGER || amount > max) {
                throw new GameException("Wager must be between $" + MIN_DAILY_DOUBLE_WAGER + " and $" + max);
            }
            dailyDoubleWager = amount;
            answeringTeamId = teamId;
            phase = Phase.CLUE;
        } else if (phase == Phase.FINAL_WAGER) {
            if (!finalEligible.contains(teamId)) {
                throw new GameException("Only teams with a positive score play Final Jeopardy");
            }
            if (amount < 0 || amount > team.score()) {
                throw new GameException("Wager must be between $0 and $" + team.score());
            }
            finalWagers.put(teamId, amount);
        } else {
            throw new GameException("There's nothing to wager on right now");
        }
    }

    public synchronized void openBuzzers() {
        touch();
        require(Phase.CLUE);
        if (isDailyDouble() || revealed || answeringTeamId != null) {
            throw new GameException("Buzzers can't open right now");
        }
        buzzersOpen = true;
    }

    /** Returns true if this team won the buzz. Late or locked-out buzzes are simply ignored. */
    public synchronized boolean buzz(String teamId) {
        touch();
        team(teamId);
        if (phase != Phase.CLUE || !buzzersOpen || answeringTeamId != null || lockedOut.contains(teamId)) {
            return false;
        }
        answeringTeamId = teamId;
        buzzersOpen = false;
        return true;
    }

    /** Host marks the answering team right or wrong. */
    public synchronized void judge(boolean correct) {
        touch();
        require(Phase.CLUE);
        if (answeringTeamId == null || revealed) {
            throw new GameException("No team is answering");
        }
        Team team = team(answeringTeamId);
        int value = isDailyDouble() ? dailyDoubleWager : currentCell.value();
        team.addScore(correct ? value : -value);
        lastResult = correct ? "correct" : "incorrect";
        if (correct) {
            controlTeamId = team.id();
            revealed = true;
        } else {
            lockedOut.add(team.id());
            answeringTeamId = null;
            if (isDailyDouble() || lockedOut.containsAll(teams.keySet())) {
                revealed = true;
            } else {
                buzzersOpen = true; // others may now buzz in
            }
        }
    }

    /** Time's up or nobody knows: show the correct response. */
    public synchronized void reveal() {
        touch();
        require(Phase.CLUE);
        revealed = true;
        buzzersOpen = false;
        answeringTeamId = null;
    }

    public synchronized void returnToBoard() {
        touch();
        require(Phase.CLUE);
        currentCell.markUsed();
        resetClueState();
        if (currentBoard.allUsed()) {
            advanceRound();
        } else {
            phase = Phase.BOARD;
        }
    }

    /** Host ends the round early (e.g. running out of time). */
    public synchronized void endRound() {
        touch();
        require(Phase.BOARD);
        advanceRound();
    }

    private void advanceRound() {
        if (currentBoard.round() == Round.JEOPARDY) {
            currentBoard = doubleJeopardyBoard;
            // As on the show, the trailing team picks first in Double Jeopardy.
            controlTeamId = teams.values().stream().min(Comparator.comparingInt(Team::score))
                    .map(Team::id).orElse(null);
            phase = Phase.BOARD;
        } else {
            finalEligible = teams.values().stream().filter(t -> t.score() > 0).map(Team::id).toList();
            phase = finalEligible.isEmpty() ? Phase.GAME_OVER : Phase.FINAL_WAGER;
        }
    }

    // ------------------------------------------------------------------ Final Jeopardy

    public synchronized void revealFinalClue() {
        touch();
        require(Phase.FINAL_WAGER);
        finalEligible.forEach(id -> finalWagers.putIfAbsent(id, 0)); // missing wager counts as $0
        phase = Phase.FINAL_ANSWER;
    }

    public synchronized void submitFinalAnswer(String teamId, String drawing, String text) {
        touch();
        require(Phase.FINAL_ANSWER);
        team(teamId);
        if (!finalEligible.contains(teamId)) {
            throw new GameException("Only teams with a positive score play Final Jeopardy");
        }
        finalAnswers.put(teamId, new FinalAnswer(cleanDrawing(drawing), clean(text, MAX_TEXT_LENGTH)));
    }

    public synchronized void lockFinalAnswers() {
        touch();
        require(Phase.FINAL_ANSWER);
        // Reveal from the lowest score up, as on the show.
        revealOrder = finalEligible.stream()
                .sorted(Comparator.comparingInt((String id) -> teams.get(id).score()))
                .toList();
        spotlightTeamId = revealOrder.isEmpty() ? null : revealOrder.get(0);
        phase = Phase.FINAL_REVEAL;
    }

    public synchronized void spotlight(String teamId) {
        touch();
        require(Phase.FINAL_REVEAL);
        if (!revealOrder.contains(teamId)) {
            throw new GameException("That team isn't in Final Jeopardy");
        }
        spotlightTeamId = teamId;
    }

    public synchronized void judgeFinal(String teamId, boolean correct) {
        touch();
        require(Phase.FINAL_REVEAL);
        if (!revealOrder.contains(teamId)) {
            throw new GameException("That team isn't in Final Jeopardy");
        }
        if (finalJudged.containsKey(teamId)) {
            throw new GameException("That team has already been judged");
        }
        int wager = finalWagers.getOrDefault(teamId, 0);
        team(teamId).addScore(correct ? wager : -wager);
        finalJudged.put(teamId, correct);
        spotlightTeamId = teamId;
    }

    /** Host ends the game (normally after Final Jeopardy, but allowed any time). */
    public synchronized void finish() {
        touch();
        phase = Phase.GAME_OVER;
    }

    public synchronized List<Team> standings() {
        return teams.values().stream().sorted(Comparator.comparingInt(Team::score).reversed()).toList();
    }

    // ------------------------------------------------------------------ Views

    /**
     * The state a particular screen is allowed to see. The board and players never receive
     * responses before they're revealed; only the host sees Daily Double locations.
     */
    public synchronized Map<String, Object> view(Viewer viewer, String viewerTeamId) {
        boolean host = viewer == Viewer.HOST;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("code", code);
        v.put("phase", phase.name());
        v.put("round", currentBoard == null ? null : currentBoard.round().name());
        v.put("controlTeamId", controlTeamId);

        List<Map<String, Object>> teamViews = new ArrayList<>();
        for (Team t : teams.values()) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("id", t.id());
            tv.put("name", t.name());
            tv.put("score", t.score());
            if (viewer != Viewer.PLAYER || t.id().equals(viewerTeamId)) {
                tv.put("drawing", t.drawing());
            }
            tv.put("voted", ballots.containsKey(t.id()));
            tv.put("finalVoted", finalBallots.containsKey(t.id()));
            tv.put("finalEligible", finalEligible.contains(t.id()));
            tv.put("wagered", phase == Phase.FINAL_WAGER && finalWagers.containsKey(t.id()));
            tv.put("answered", finalAnswers.containsKey(t.id()));
            teamViews.add(tv);
        }
        v.put("teams", teamViews);

        if (phase == Phase.VOTING) {
            v.put("maxVotes", BOARD_CATEGORIES);
            v.put("pool", keywordViews(pool, tally(ballots.values()), viewer != Viewer.PLAYER));
        }
        if (phase == Phase.FINAL_VOTING) {
            List<List<Long>> votes = finalBallots.values().stream().map(List::of).toList();
            v.put("finalOptions", keywordViews(finalOptions, tally(votes), viewer != Viewer.PLAYER));
        }
        if (currentBoard != null && (phase == Phase.BOARD || phase == Phase.DAILY_DOUBLE_WAGER || phase == Phase.CLUE)) {
            v.put("board", boardView(host));
        }
        if (currentCell != null && (phase == Phase.DAILY_DOUBLE_WAGER || phase == Phase.CLUE)) {
            v.put("clue", clueView(host));
        }
        if (phase.compareTo(Phase.FINAL_WAGER) >= 0 && finalCategory != null) {
            v.put("final", finalView(viewer, viewerTeamId));
        }
        if (phase == Phase.GAME_OVER) {
            v.put("standings", standings().stream().map(t -> Map.of("id", t.id(), "name", t.name(),
                    "score", t.score())).toList());
        }
        if (viewer == Viewer.PLAYER && viewerTeamId != null && teams.containsKey(viewerTeamId)) {
            v.put("me", playerView(viewerTeamId));
        }
        return v;
    }

    private List<Map<String, Object>> keywordViews(List<KeywordOption> options, Map<Long, Integer> votes,
            boolean showVotes) {
        return options.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.id());
            m.put("title", k.displayTitle());
            m.put("keyword", k.name());
            if (showVotes) {
                m.put("votes", votes.getOrDefault(k.id(), 0));
            }
            return m;
        }).toList();
    }

    private Map<String, Object> boardView(boolean host) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("round", currentBoard.round().name());
        b.put("categories", currentBoard.categories().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("title", c.keyword().displayTitle());
            cm.put("keyword", c.keyword().name());
            cm.put("cells", c.cells().stream().map(cell -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("value", cell.value());
                m.put("used", cell.used());
                if (host) {
                    m.put("dailyDouble", cell.dailyDouble());
                }
                return m;
            }).toList());
            return cm;
        }).toList());
        return b;
    }

    private Map<String, Object> clueView(boolean host) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("column", currentCell.column());
        c.put("row", currentCell.row());
        c.put("value", currentCell.value());
        c.put("category", currentBoard.categories().get(currentCell.column()).keyword().displayTitle());
        c.put("dailyDouble", isDailyDouble());
        c.put("dailyDoubleTeamId", dailyDoubleTeamId);
        c.put("wager", dailyDoubleWager);
        if (phase == Phase.CLUE || host) {
            c.put("text", currentCell.clue().clue());
        }
        c.put("buzzersOpen", buzzersOpen);
        c.put("answeringTeamId", answeringTeamId);
        c.put("lockedOut", List.copyOf(lockedOut));
        c.put("revealed", revealed);
        c.put("lastResult", lastResult);
        if (host || revealed) {
            c.put("response", currentCell.clue().response());
        }
        return c;
    }

    private Map<String, Object> finalView(Viewer viewer, String viewerTeamId) {
        boolean host = viewer == Viewer.HOST;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("category", finalCategory.displayTitle());
        if (phase.compareTo(Phase.FINAL_ANSWER) >= 0) {
            f.put("text", finalClue.clue());
        }
        boolean responseVisible = host || phase == Phase.GAME_OVER || !finalJudged.isEmpty();
        if (responseVisible) {
            f.put("response", finalClue.response());
        }
        f.put("revealOrder", revealOrder);
        f.put("spotlightTeamId", spotlightTeamId);
        f.put("judged", Map.copyOf(finalJudged));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String id : finalEligible) {
            boolean own = id.equals(viewerTeamId);
            boolean shown = host || own || (viewer == Viewer.BOARD && phase.compareTo(Phase.FINAL_REVEAL) >= 0
                    && (id.equals(spotlightTeamId) || finalJudged.containsKey(id)));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("teamId", id);
            if (shown) {
                e.put("wager", finalWagers.get(id));
                FinalAnswer a = finalAnswers.get(id);
                e.put("drawing", a == null ? null : a.drawing());
                e.put("text", a == null ? null : a.text());
            }
            entries.add(e);
        }
        f.put("entries", entries);
        return f;
    }

    private Map<String, Object> playerView(String teamId) {
        Team t = teams.get(teamId);
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", t.id());
        me.put("name", t.name());
        me.put("score", t.score());
        me.put("ballot", ballots.getOrDefault(teamId, Set.of()));
        me.put("finalVote", finalBallots.get(teamId));
        me.put("canBuzz", phase == Phase.CLUE && !isDailyDouble() && !revealed && answeringTeamId == null
                && !lockedOut.contains(teamId));
        me.put("lockedOut", lockedOut.contains(teamId));
        me.put("answering", teamId.equals(answeringTeamId));
        boolean ddWager = phase == Phase.DAILY_DOUBLE_WAGER && teamId.equals(dailyDoubleTeamId);
        me.put("dailyDoubleWager", ddWager);
        if (ddWager) {
            me.put("wagerMin", MIN_DAILY_DOUBLE_WAGER);
            me.put("wagerMax", dailyDoubleMaxWager(teamId));
        }
        boolean eligible = finalEligible.contains(teamId);
        me.put("finalEligible", eligible);
        me.put("finalWager", finalWagers.get(teamId));
        if (phase == Phase.FINAL_WAGER && eligible) {
            me.put("wagerMin", 0);
            me.put("wagerMax", t.score());
        }
        me.put("finalAnswered", finalAnswers.containsKey(teamId));
        return me;
    }

    // ------------------------------------------------------------------ Helpers

    private boolean isDailyDouble() {
        return currentCell != null && currentCell.dailyDouble();
    }

    private void resetClueState() {
        currentCell = null;
        buzzersOpen = false;
        answeringTeamId = null;
        lockedOut.clear();
        revealed = false;
        dailyDoubleTeamId = null;
        dailyDoubleWager = null;
        lastResult = null;
    }

    private Map<Long, Integer> tally(Collection<? extends Collection<Long>> votes) {
        Map<Long, Integer> counts = new HashMap<>();
        votes.forEach(b -> b.forEach(id -> counts.merge(id, 1, Integer::sum)));
        return counts;
    }

    private Team team(String teamId) {
        Team team = teamId == null ? null : teams.get(teamId);
        if (team == null) {
            throw new GameException("Unknown team");
        }
        return team;
    }

    private void require(Phase expected) {
        if (phase != expected) {
            throw new GameException("Can't do that during " + phase.name().replace('_', ' ').toLowerCase());
        }
    }

    private void touch() {
        lastActivity = clock.instant();
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String cleanDrawing(String drawing) {
        if (drawing == null || !drawing.startsWith(PNG_PREFIX) || drawing.length() > MAX_DRAWING_LENGTH) {
            return null;
        }
        return drawing;
    }
}
