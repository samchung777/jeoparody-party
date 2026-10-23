package party.jeoparody.game;

/** An action that isn't allowed right now (wrong phase, bad wager, unknown team...). Shown to the user. */
public class GameException extends RuntimeException {

    public GameException(String message) {
        super(message);
    }
}
