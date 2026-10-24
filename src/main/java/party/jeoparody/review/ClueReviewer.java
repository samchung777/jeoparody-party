package party.jeoparody.review;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Decides which clues are behind-the-scenes trivia. Gemini in the app, faked in tests. */
public interface ClueReviewer {

    /** Returns clue id -> short reason, for clues that should be retired. */
    Map<Long, String> offTopic(List<ClueToReview> batch) throws IOException, InterruptedException;
}
