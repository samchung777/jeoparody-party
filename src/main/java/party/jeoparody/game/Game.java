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
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One game room and all of its rules. Pure Java (no Spring, no I/O) so every rule is unit-testable.
 * All public methods are synchronized: the host, board and every player device call in concurrently.
 *
 * Where it fits: GameService keeps one Game per room code in memory; GameSocketHandler turns each WebSocket
 * message into one call here, then sends every screen a fresh {@link #view}. Results go to MySQL only at the end.
 * Why it matters: this is the whole rule book. Every action checks the current {@link Phase} first
 * ({@link #require}), which is why an action at the wrong moment fails with "Can't do that during ...".
 * GameTest covers it without a database; GameWebSocketIT plays it end to end over real sockets.
 */
public final class Game {

    /**
     * Board shape and limits, as on the show: 6 categories x 5 clues per round, 12 categories over two rounds,
     * a random pool of 26 keywords to vote from, and a category needs at least 5 clues to be playable.
     */
    public static final int ROWS = 5;
    public static final int COLUMNS = 6;
    public static final int BOARD_CATEGORIES = COLUMNS * 2;
    public static final int POOL_SIZE = 26;
    public static final int MIN_CLUES_PER_KEYWORD = ROWS;
    public static final int MIN_DAILY_DOUBLE_WAGER = 5;
    public static final int MAX_TEAMS = 12;
    public static final int MAX_SEARCH_RESULTS = 20;
    public static final int MAX_BROWSE_RESULTS = 40;
    static final int MAX_NAME_LENGTH = 24;
    static final int MAX_DRAWING_LENGTH = 300_000;
    static final int MAX_TEXT_LENGTH = 120;
    private static final String PNG_PREFIX = "data:image/png;base64,";

    /** What kind of screen is asking for the game state. */
    public enum Viewer { HOST, BOARD, PLAYER }

    /**
     * A team's Final Jeopardy response: a drawing (PNG data URL from the phone's drawing pad) and/or typed text.
     */
    record FinalAnswer(String drawing, String text) {
    }

    /** A clue that has been shown in this game, for the host's post-game review. */
    record PlayedClue(CandidateClue clue, String category, int value, long keywordId, String keyword) {
    }

    /** Host feedback actions: retire the clue, or have Gemini reword it. */
    public static final Set<String> FEEDBACK_ACTIONS = Set.of("remove", "reword");

    private final String code;
    private final String hostKey;
    private final BoardBuilder builder;
    private final Supplier<String> tokens;
    private final Clock clock;
    private final Instant createdAt;
    private Instant lastActivity;
    private Instant startedAt;
    private int gameNumber = 1;
    /** The host's theme: narrows which keywords this room offers (Everything by default). */
    private Themes.Theme theme = Themes.EVERYTHING;
    /** The host's difficulty setting: which clue difficulties fill the board (Normal by default). */
    private Difficulty difficulty = Difficulty.NORMAL;

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private Phase phase = Phase.LOBBY;

    // Voting: a random pool, plus anything teams find with search
    private List<KeywordOption> eligible = List.of();
    private final List<KeywordOption> pool = new ArrayList<>();
    private final Map<String, Set<Long>> ballots = new HashMap<>();
    private final List<KeywordOption> finalOptions = new ArrayList<>();
    /** Keywords already on the two boards: can't also be Final Jeopardy. */
    private final Set<Long> boardKeywordIds = new HashSet<>();
    private final Map<String, Long> finalBallots = new HashMap<>();

    // Boards
    private Board jeopardyBoard;
    private Board doubleJeopardyBoard;
    private Board currentBoard;
    private final Set<Long> usedClueIds = new HashSet<>();
    /** Clues actually shown in earlier games tonight: never reused. Unplayed squares become available again. */
    private final Set<Long> shownInEarlierGames = new HashSet<>();
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

    // Host feedback on clues
    private final List<PlayedClue> played = new ArrayList<>();
    private final Map<Long, String> feedback = new HashMap<>();
    /** Categories (keywords) the host removed from future games during this game. */
    private final Set<Long> removedKeywords = new HashSet<>();
    /** Clues shown since GameService last asked: their staleness is increased in MySQL. */
    private final List<Long> newlyShown = new ArrayList<>();
    /** True after a wrong answer reopened the buzzers for everyone else (shown as "Rebound!"). */
    private boolean rebound;

    /**
     * Created by GameService. The token supplier and clock are passed in (not created here) so tests can use
     * fixed values: that is what makes the engine deterministic and unit-testable.
     */
    public Game(String code, String hostKey, BoardBuilder builder, Supplier<String> tokens, Clock clock) {
        this.code = code;
        this.hostKey = hostKey;
        this.builder = builder;
        this.tokens = tokens;
        this.clock = clock;
        this.createdAt = clock.instant();
        this.lastActivity = createdAt;
        this.startedAt = createdAt;
    }

    public String code() { return code; }
    public Instant createdAt() { return createdAt; }
    /** When the current game (this room may host several in a row) started. */
    public synchronized Instant startedAt() { return startedAt; }
    /** 1 for the first game in this room, 2 after "play again", ... */
    public synchronized int gameNumber() { return gameNumber; }
    public synchronized Instant lastActivity() { return lastActivity; }
    public synchronized Phase phase() { return phase; }

    /**
     * Checks the secret host key from the host link. Uses a constant-time comparison (MessageDigest.isEqual)
     * so the key can't be guessed by timing how long the comparison takes.
     */
    public boolean isHostKey(String key) {
        return key != null && java.security.MessageDigest.isEqual(hostKey.getBytes(), key.getBytes());
    }

    /** A snapshot copy, so callers can't change the game's team list from outside. */
    public synchronized List<Team> teams() {
        return List.copyOf(teams.values());
    }

    // ------------------------------------------------------------------ Teams

    /**
     * A phone joins the room with a team name and its drawn name tag. Names and drawings are cleaned and size-capped
     * (user input). Each team gets a random id and a secret token, which the phone stores to reconnect later.
     */
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

    /**
     * A phone that lost its connection proves it is the same team with the token it saved, and carries on.
     */
    public synchronized Team rejoin(String teamId, String token) {
        Team team = teams.get(teamId);
        if (team == null || token == null || !team.token().equals(token)) {
            throw new GameException("Your team isn't in this game any more. Join again.");
        }
        return team;
    }

    /**
     * Host removes a team, cleaning up every place that team could be referenced (votes, lockouts, control, Final).
     */
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

    /** Host corrects a score by hand (any phase): the escape hatch for judging mistakes. */
    public synchronized void adjustScore(String teamId, int delta) {
        touch();
        team(teamId).addScore(delta);
    }

    /** Host chooses which team picks the next clue (and so who answers a Daily Double). */
    public synchronized void setControl(String teamId) {
        touch();
        team(teamId);
        controlTeamId = teamId;
        if (phase == Phase.DAILY_DOUBLE_WAGER) {
            dailyDoubleTeamId = teamId;
        }
    }

    // ------------------------------------------------------------------ Themes and genres

    /** The current theme's name. */
    public synchronized String themeName() {
        return theme.name();
    }

    /**
     * Host picks a theme in the lobby (or after a game, for the next one). Only keywords whose genres overlap
     * the theme are offered in votes, search and genre browsing.
     */
    public synchronized void setTheme(String name) {
        touch();
        if (phase != Phase.LOBBY && phase != Phase.GAME_OVER) {
            throw new GameException("Pick a theme before the vote starts");
        }
        theme = Themes.find(name).orElseThrow(() -> new GameException("Unknown theme"));
    }

    /** The current difficulty setting. */
    public synchronized Difficulty difficulty() {
        return difficulty;
    }

    /**
     * Host picks Easy, Normal or Hard before the boards are built (lobby, voting, or after a game for the next
     * one). Boards are built when the vote closes, so the setting applies to the whole game.
     */
    public synchronized void setDifficulty(String name) {
        touch();
        if (phase != Phase.LOBBY && phase != Phase.VOTING && phase != Phase.GAME_OVER) {
            throw new GameException("Difficulty can only change before the board is built");
        }
        difficulty = Difficulty.find(name).orElseThrow(() -> new GameException("Unknown difficulty"));
    }

    /**
     * Lists every playable keyword in one genre (within the theme), most clues first, so phones can browse the
     * vote by general category instead of only the random pool. Same shape as search results.
     */
    public synchronized List<Map<String, Object>> browseGenre(String genre) {
        if (phase != Phase.VOTING && phase != Phase.FINAL_VOTING) {
            throw new GameException("Browsing is only for the category votes");
        }
        if (genre == null || !Themes.GENRES.contains(genre)) {
            throw new GameException("Unknown genre");
        }
        boolean finalVote = phase == Phase.FINAL_VOTING;
        Set<Long> inPool = new HashSet<>();
        (finalVote ? finalOptions : pool).forEach(k -> inPool.add(k.id()));
        return eligible.stream()
                .filter(k -> k.genres().contains(genre))
                .filter(k -> !finalVote || !boardKeywordIds.contains(k.id()))
                .sorted(Comparator.comparingInt(KeywordOption::clueCount).reversed()
                        .thenComparing(KeywordOption::displayTitle))
                .limit(MAX_BROWSE_RESULTS)
                .map(k -> keywordHit(k, inPool))
                .toList();
    }

    private static Map<String, Object> keywordHit(KeywordOption k, Set<Long> inPool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("title", k.displayTitle());
        m.put("keyword", k.name());
        m.put("inPool", inPool.contains(k.id()));
        return m;
    }

    /** How many playable keywords each genre has in this room's theme (for the phones' genre buttons). */
    private List<Map<String, Object>> genreCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Themes.GENRES.forEach(g -> counts.put(g, 0));
        eligible.forEach(k -> k.genres().forEach(g -> counts.computeIfPresent(g, (key, n) -> n + 1)));
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((g, n) -> {
            if (n > 0) {
                out.add(Map.of("name", g, "count", n));
            }
        });
        return out;
    }

    /**
     * The clue ids shown since the last call (each played clue once). GameService saves them as +1 staleness.
     */
    public synchronized List<Long> drainShownClueIds() {
        List<Long> out = List.copyOf(newlyShown);
        newlyShown.clear();
        return out;
    }

    // ------------------------------------------------------------------ Voting

    /**
     * LOBBY -> VOTING. Loads every keyword with enough clues (from MySQL, through ClueSource) and draws the random
     * pool of 26 that phones vote on. Fails clearly if the database doesn't have enough trivia yet.
     */
    public synchronized void startVoting(ClueSource source) {
        touch();
        require(Phase.LOBBY);
        if (teams.isEmpty()) {
            throw new GameException("Wait for at least one team to join");
        }
        List<KeywordOption> available = source.eligibleKeywords(MIN_CLUES_PER_KEYWORD).stream()
                .filter(k -> theme.matches(k.genres()))
                .toList();
        if (available.size() < BOARD_CATEGORIES + 1) {
            throw new GameException(theme == Themes.EVERYTHING
                    ? "Only " + available.size() + " keywords have enough clues; need " + (BOARD_CATEGORIES + 1)
                            + ". Import more trivia first."
                    : "The " + theme.name() + " theme only has " + available.size() + " categories; need "
                            + (BOARD_CATEGORIES + 1) + ". Pick a broader theme.");
        }
        eligible = List.copyOf(available);
        pool.clear();
        pool.addAll(builder.pickPool(eligible, POOL_SIZE));
        phase = Phase.VOTING;
    }

    /**
     * Searches every playable keyword (not just the random pool) by keyword or title. Every word typed must
     * appear; accents and case don't matter.
     */
    public synchronized List<Map<String, Object>> searchKeywords(String query) {
        if (phase != Phase.VOTING && phase != Phase.FINAL_VOTING) {
            throw new GameException("Searching is only for the category votes");
        }
        boolean finalVote = phase == Phase.FINAL_VOTING;
        List<String> words = List.of(fold(query).split(" ")).stream().filter(w -> !w.isBlank()).toList();
        if (words.isEmpty()) {
            return List.of();
        }
        Set<Long> inPool = new HashSet<>();
        (finalVote ? finalOptions : pool).forEach(k -> inPool.add(k.id()));
        return eligible.stream()
                .filter(k -> !finalVote || !boardKeywordIds.contains(k.id())) // already on a board
                .filter(k -> {
                    String haystack = fold(k.name() + " " + k.displayTitle());
                    return words.stream().allMatch(haystack::contains);
                })
                .sorted(Comparator.comparing((KeywordOption k) -> !fold(k.name()).startsWith(words.get(0)))
                        .thenComparing(Comparator.comparingInt(KeywordOption::clueCount).reversed()))
                .limit(MAX_SEARCH_RESULTS)
                .map(k -> keywordHit(k, inPool))
                .toList();
    }

    /** Search helper: lower-case, strip accents ('Pokémon' matches 'pokemon') and punctuation. */
    private static String fold(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * A team's board vote: up to 12 keywords. Voting again replaces the team's ballot. A keyword found by search
     * joins the shared pool so everyone can see it and vote for it too.
     */
    public synchronized void vote(String teamId, Collection<Long> keywordIds) {
        touch();
        require(Phase.VOTING);
        team(teamId);
        Set<Long> ballot = new LinkedHashSet<>(keywordIds);
        if (ballot.size() > BOARD_CATEGORIES) {
            throw new GameException("Pick at most " + BOARD_CATEGORIES + " keywords");
        }
        Map<Long, KeywordOption> playable = new HashMap<>();
        eligible.forEach(k -> playable.put(k.id(), k));
        if (!playable.keySet().containsAll(ballot)) {
            throw new GameException("That category isn't available");
        }
        // A category found with search joins the shared list, so every screen can see and vote for it.
        Set<Long> poolIds = new HashSet<>();
        pool.forEach(k -> poolIds.add(k.id()));
        for (Long id : ballot) {
            if (poolIds.add(id)) {
                pool.add(playable.get(id));
            }
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
                    usedClueIds, round, target.size(), difficulty);
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

        boardKeywordIds.clear();
        boardKeywordIds.addAll(chosen);
        finalOptions.clear();
        pool.stream().filter(k -> !chosen.contains(k.id())).forEach(finalOptions::add);
        if (finalOptions.isEmpty()) {
            // Everything in the pool made the boards: offer anything else that's playable.
            eligible.stream().filter(k -> !chosen.contains(k.id())).limit(POOL_SIZE).forEach(finalOptions::add);
        }
        phase = Phase.FINAL_VOTING;
    }

    /** A team's vote for the Final Jeopardy category (one keyword not already on a board). */
    public synchronized void voteFinal(String teamId, long keywordId) {
        touch();
        require(Phase.FINAL_VOTING);
        team(teamId);
        finalBallots.put(teamId, finalOption(keywordId).id());
    }

    /**
     * Any playable keyword that isn't on a board can be the Final category; one found with search
     * joins the shared list of options.
     */
    private KeywordOption finalOption(long keywordId) {
        Optional<KeywordOption> listed = finalOptions.stream().filter(k -> k.id() == keywordId).findFirst();
        if (listed.isPresent()) {
            return listed.get();
        }
        if (boardKeywordIds.contains(keywordId)) {
            throw new GameException("That category is already on a board");
        }
        KeywordOption found = eligible.stream().filter(k -> k.id() == keywordId).findFirst()
                .orElseThrow(() -> new GameException("That category isn't available"));
        finalOptions.add(found);
        return found;
    }

    /** Picks the most-voted Final category and starts the Jeopardy round. */
    public synchronized void closeFinalVoting(ClueSource source) {
        closeFinalVoting(source, null);
    }

    /**
     * Starts the Jeopardy round with a Final category: the host's choice if given (typed in and searched
     * for), otherwise the most-voted option.
     */
    public synchronized void closeFinalVoting(ClueSource source, Long hostChoice) {
        touch();
        require(Phase.FINAL_VOTING);
        List<Long> ranked;
        if (hostChoice != null) {
            ranked = List.of(finalOption(hostChoice).id());
        } else {
            List<List<Long>> votes = finalBallots.values().stream().map(List::of).toList();
            ranked = builder.rank(votes, finalOptions);
        }
        for (Long id : ranked) {
            Optional<CandidateClue> clue = builder.pickFinalClue(source.cluesForKeyword(id), usedClueIds,
                    difficulty);
            if (clue.isPresent()) {
                finalCategory = finalOptions.stream().filter(k -> k.id() == id).findFirst().orElseThrow();
                finalClue = clue.get();
                usedClueIds.add(finalClue.id());
                break;
            }
        }
        if (finalClue == null) {
            throw new GameException(hostChoice != null ? "That category has no unused clue left; pick another"
                    : "No unused clue available for Final Jeopardy");
        }
        currentBoard = jeopardyBoard;
        if (controlTeamId == null && !teams.isEmpty()) {
            List<String> ids = new ArrayList<>(teams.keySet());
            controlTeamId = ids.get(Math.floorMod(code.hashCode(), ids.size()));
        }
        phase = Phase.BOARD;
    }

    // ------------------------------------------------------------------ Board play

    /**
     * BOARD -> CLUE (or DAILY_DOUBLE_WAGER). The host picks a square; a Daily Double goes to the team in control,
     * which must wager before seeing the clue.
     */
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

    /**
     * Host opens the buzzers after reading the clue. Not allowed on a Daily Double (only one team plays it),
     * after the response is revealed, or while a team is already answering.
     */
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
        rebound = false;
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
                buzzersOpen = true; // the remaining teams can buzz straight away: no need to reopen by hand
                rebound = true;
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

    /**
     * CLUE -> BOARD. Marks the square used and records it for the host's feedback panel. When every square on the
     * board is used, moves on to the next round (or Final Jeopardy).
     */
    public synchronized void returnToBoard() {
        touch();
        require(Phase.CLUE);
        currentCell.markUsed();
        KeywordOption playedKeyword = currentBoard.categories().get(currentCell.column()).keyword();
        played.add(new PlayedClue(currentCell.clue(), playedKeyword.displayTitle(), currentCell.value(),
                playedKeyword.id(), playedKeyword.name()));
        newlyShown.add(currentCell.clue().id());
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

    /**
     * Jeopardy -> Double Jeopardy (trailing team picks first), or Double Jeopardy -> Final Jeopardy.
     * Only teams with a positive score play Final; if none, the game ends.
     */
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

    /**
     * FINAL_WAGER -> FINAL_ANSWER. Shows the Final clue; a team that didn't wager is treated as wagering $0.
     */
    public synchronized void revealFinalClue() {
        touch();
        require(Phase.FINAL_WAGER);
        finalEligible.forEach(id -> finalWagers.putIfAbsent(id, 0)); // missing wager counts as $0
        played.add(new PlayedClue(finalClue, "Final Jeopardy: " + finalCategory.displayTitle(), 0,
                finalCategory.id(), finalCategory.name()));
        newlyShown.add(finalClue.id());
        phase = Phase.FINAL_ANSWER;
    }

    /**
     * A team's written Final response, from the phone's drawing pad. Can be resubmitted until the host locks answers.
     */
    public synchronized void submitFinalAnswer(String teamId, String drawing, String text) {
        touch();
        require(Phase.FINAL_ANSWER);
        team(teamId);
        if (!finalEligible.contains(teamId)) {
            throw new GameException("Only teams with a positive score play Final Jeopardy");
        }
        finalAnswers.put(teamId, new FinalAnswer(cleanDrawing(drawing), clean(text, MAX_TEXT_LENGTH)));
    }

    /**
     * FINAL_ANSWER -> FINAL_REVEAL. No more changes; sets the reveal order from lowest score up, as on the show.
     */
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

    /**
     * Shows one team's Final answer on the TV board, so the room sees it before the host judges it.
     */
    public synchronized void spotlight(String teamId) {
        touch();
        require(Phase.FINAL_REVEAL);
        if (!revealOrder.contains(teamId)) {
            throw new GameException("That team isn't in Final Jeopardy");
        }
        spotlightTeamId = teamId;
    }

    /**
     * Host judges one team's Final answer: adds or subtracts the wager. Each team can only be judged once.
     */
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

    /**
     * Records that the host flagged a clue from this game. The caller persists it; this only
     * validates the clue belongs to this game and remembers the choice so the host UI can show it.
     */
    public synchronized void recordFeedback(long clueId, String action) {
        touch();
        if (!FEEDBACK_ACTIONS.contains(action)) {
            throw new GameException("Unknown feedback action");
        }
        if (!containsClue(clueId)) {
            throw new GameException("That clue isn't part of this game");
        }
        feedback.put(clueId, action);
    }

    /** Records that the host removed a whole category (keyword) used in this game from future games. */
    public synchronized void recordCategoryRemoval(long keywordId) {
        touch();
        boolean inGame = boardKeywordIds.contains(keywordId)
                || (finalCategory != null && finalCategory.id() == keywordId);
        if (!inGame) {
            throw new GameException("That category isn't part of this game");
        }
        removedKeywords.add(keywordId);
    }

    /**
     * True if the clue is on either board or is the Final clue: feedback is only accepted for this game's clues.
     */
    private boolean containsClue(long clueId) {
        if (finalClue != null && finalClue.id() == clueId) {
            return true;
        }
        for (Board b : new Board[] {jeopardyBoard, doubleJeopardyBoard}) {
            if (b != null && b.categories().stream().flatMap(c -> c.cells().stream())
                    .anyMatch(cell -> cell.clue().id() == clueId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts a new game in the same room with the same teams (names, drawings, phones stay connected).
     * Scores reset and play goes straight to the category vote. Clues used earlier tonight stay used,
     * so the new boards don't repeat them.
     */
    public synchronized void playAgain(ClueSource source) {
        touch();
        require(Phase.GAME_OVER);
        if (teams.isEmpty()) {
            throw new GameException("No teams left to play again");
        }
        teams.values().forEach(Team::resetScore);
        played.forEach(p -> shownInEarlierGames.add(p.clue().id()));
        usedClueIds.clear();
        usedClueIds.addAll(shownInEarlierGames);
        eligible = List.of();
        pool.clear();
        ballots.clear();
        finalOptions.clear();
        boardKeywordIds.clear();
        removedKeywords.clear();
        finalBallots.clear();
        jeopardyBoard = null;
        doubleJeopardyBoard = null;
        currentBoard = null;
        controlTeamId = null;
        resetClueState();
        finalCategory = null;
        finalClue = null;
        finalEligible = List.of();
        finalWagers.clear();
        finalAnswers.clear();
        finalJudged.clear();
        revealOrder = List.of();
        spotlightTeamId = null;
        played.clear();
        feedback.clear();
        gameNumber++;
        startedAt = clock.instant();
        phase = Phase.LOBBY;
        startVoting(source); // throws (and stays in the lobby) if there isn't enough trivia left
    }

    /** Host ends the game (normally after Final Jeopardy, but allowed any time). */
    public synchronized void finish() {
        touch();
        phase = Phase.GAME_OVER;
    }

    /**
     * Teams ordered by score, highest first: the GAME_OVER screen and the saved game_result use this.
     */
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
        v.put("gameNumber", gameNumber);
        v.put("round", currentBoard == null ? null : currentBoard.round().name());
        v.put("controlTeamId", controlTeamId);
        v.put("theme", theme.name());
        v.put("difficulty", difficulty.label());

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

        if (phase == Phase.VOTING || phase == Phase.FINAL_VOTING) {
            v.put("genres", genreCounts());
        }
        if (phase == Phase.VOTING) {
            v.put("maxVotes", BOARD_CATEGORIES);
            v.put("searchableCount", eligible.size());
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
        if (host && phase == Phase.GAME_OVER) {
            v.put("played", played.stream().map(pc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("clueId", pc.clue().id());
                m.put("category", pc.category());
                m.put("value", pc.value());
                m.put("clue", pc.clue().clue());
                m.put("response", pc.clue().response());
                m.put("feedback", feedback.get(pc.clue().id()));
                m.put("keywordId", pc.keywordId());
                m.put("keyword", pc.keyword());
                m.put("categoryRemoved", removedKeywords.contains(pc.keywordId()));
                return m;
            }).toList());
        }
        if (viewer == Viewer.PLAYER && viewerTeamId != null && teams.containsKey(viewerTeamId)) {
            v.put("me", playerView(viewerTeamId));
        }
        return v;
    }

    /**
     * Turns keyword options into JSON-ready maps; vote counts only for the host and board, never for phones.
     */
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

    /** The grid as screens draw it. Daily Double positions are included only when host is true. */
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

    /**
     * The clue on screen. The correct response is sent to the host always, to others only once revealed.
     */
    private Map<String, Object> clueView(boolean host) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("column", currentCell.column());
        c.put("row", currentCell.row());
        c.put("value", currentCell.value());
        KeywordOption categoryKeyword = currentBoard.categories().get(currentCell.column()).keyword();
        c.put("category", categoryKeyword.displayTitle());
        c.put("keyword", categoryKeyword.name());
        if (host) {
            c.put("keywordId", categoryKeyword.id());
            c.put("categoryRemoved", removedKeywords.contains(categoryKeyword.id()));
        }
        c.put("dailyDouble", isDailyDouble());
        c.put("dailyDoubleTeamId", dailyDoubleTeamId);
        c.put("wager", dailyDoubleWager);
        if (phase == Phase.CLUE || host) {
            c.put("text", currentCell.clue().clue());
            // Hint tags: the clue's other keywords, minus any that would give the answer away.
            c.put("tags", ClueTags.safe(currentCell.clue().keywords(), currentCell.clue().response(),
                    categoryKeyword.name()));
        }
        c.put("buzzersOpen", buzzersOpen);
        c.put("rebound", rebound && buzzersOpen);
        c.put("answeringTeamId", answeringTeamId);
        c.put("lockedOut", List.copyOf(lockedOut));
        c.put("revealed", revealed);
        c.put("lastResult", lastResult);
        if (host || revealed) {
            c.put("response", currentCell.clue().response());
        }
        if (host) {
            c.put("clueId", currentCell.clue().id());
            c.put("feedback", feedback.get(currentCell.clue().id()));
        }
        return c;
    }

    /**
     * Final Jeopardy state: category, clue once shown; the board sees an answer only when spotlighted or judged.
     */
    private Map<String, Object> finalView(Viewer viewer, String viewerTeamId) {
        boolean host = viewer == Viewer.HOST;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("category", finalCategory.displayTitle());
        f.put("keyword", finalCategory.name());
        if (phase.compareTo(Phase.FINAL_ANSWER) >= 0) {
            f.put("text", finalClue.clue());
            f.put("tags", ClueTags.safe(finalClue.keywords(), finalClue.response(), finalCategory.name()));
        }
        boolean responseVisible = host || phase == Phase.GAME_OVER || !finalJudged.isEmpty();
        if (responseVisible) {
            f.put("response", finalClue.response());
        }
        if (host) {
            f.put("clueId", finalClue.id());
            f.put("feedback", feedback.get(finalClue.id()));
            f.put("keywordId", finalCategory.id());
            f.put("categoryRemoved", removedKeywords.contains(finalCategory.id()));
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

    /** What one phone sees about its own team: score, whether it can buzz, its wager limits. */
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

    /** True when the clue on screen is a Daily Double (scored by wager, not square value). */
    private boolean isDailyDouble() {
        return currentCell != null && currentCell.dailyDouble();
    }

    /**
     * Clears everything about the previous clue (buzzers, answering team, lockouts, wager) before the next one.
     */
    private void resetClueState() {
        currentCell = null;
        buzzersOpen = false;
        answeringTeamId = null;
        lockedOut.clear();
        revealed = false;
        dailyDoubleTeamId = null;
        dailyDoubleWager = null;
        lastResult = null;
        rebound = false;
    }

    /** Counts votes per keyword across all ballots. */
    private Map<Long, Integer> tally(Collection<? extends Collection<Long>> votes) {
        Map<Long, Integer> counts = new HashMap<>();
        votes.forEach(b -> b.forEach(id -> counts.merge(id, 1, Integer::sum)));
        return counts;
    }

    /** Looks up a team or fails with a clear message (guards every team-specific action). */
    private Team team(String teamId) {
        Team team = teamId == null ? null : teams.get(teamId);
        if (team == null) {
            throw new GameException("Unknown team");
        }
        return team;
    }

    /**
     * The phase guard used by almost every action. Throws GameException ("Can't do that during board"), which
     * GameSocketHandler sends back to that one screen as an error message. The CI race we fixed showed up as this error.
     */
    private void require(Phase expected) {
        if (phase != expected) {
            throw new GameException("Can't do that during " + phase.name().replace('_', ' ').toLowerCase());
        }
    }

    /** Records activity, so GameService can clean up rooms nobody has used for a while. */
    private void touch() {
        lastActivity = clock.instant();
    }

    /** Trims and caps user-typed text (team names, Final answers). */
    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** Accepts only PNG data URLs under the size limit: drawings come from untrusted phones. */
    private static String cleanDrawing(String drawing) {
        if (drawing == null || !drawing.startsWith(PNG_PREFIX) || drawing.length() > MAX_DRAWING_LENGTH) {
            return null;
        }
        return drawing;
    }
}
