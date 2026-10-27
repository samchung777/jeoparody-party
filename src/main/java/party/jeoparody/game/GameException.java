package party.jeoparody.game;

/** An action that isn't allowed right now (wrong phase, bad wager, unknown team...). Shown to the user. */
public class GameException extends RuntimeException {

    /** The message is shown to the user as-is, so it is written for people, not developers. */
    public GameException(String message) {
        super(message);
    }
}
