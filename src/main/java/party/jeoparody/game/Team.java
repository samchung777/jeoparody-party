package party.jeoparody.game;

/** A team (one player device). The token lets that device reconnect after a refresh. */
public final class Team {

    private final String id;
    private final String token;
    private final String name;
    private final String drawing;
    private int score;

    Team(String id, String token, String name, String drawing) {
        this.id = id;
        this.token = token;
        this.name = name;
        this.drawing = drawing;
    }

    public String id() { return id; }
    public String token() { return token; }
    public String name() { return name; }
    /** PNG data URL of the team name drawn on the player's pad (may be null). */
    public String drawing() { return drawing; }
    public int score() { return score; }

    void addScore(int delta) { score += delta; }
}
