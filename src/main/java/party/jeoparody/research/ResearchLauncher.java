package party.jeoparody.research;

/** Starts processing the research queue somewhere: a background thread, or a separate Cloud Run Job. */
public interface ResearchLauncher {

    /** Asks for the queue to be worked through. Returns at once; never throws. */
    void launch();

    /** Where the work runs, for logs and the library page ("background thread", "Cloud Run Job ..."). */
    String describe();
}
