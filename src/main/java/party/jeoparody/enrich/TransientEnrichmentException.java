package party.jeoparody.enrich;

import java.io.IOException;

/** A temporary failure (rate limit, server overload) that is worth retrying after a back-off. */
public class TransientEnrichmentException extends IOException {

    /**
     * Thrown by GeminiClient for HTTP 429/500/502/503/504; callers retry with back-off instead of failing.
     */
    public TransientEnrichmentException(String message) {
        super(message);
    }
}
