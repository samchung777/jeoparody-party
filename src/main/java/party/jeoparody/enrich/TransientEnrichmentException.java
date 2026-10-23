package party.jeoparody.enrich;

import java.io.IOException;

/** A temporary failure (rate limit, server overload) that is worth retrying after a back-off. */
public class TransientEnrichmentException extends IOException {

    public TransientEnrichmentException(String message) {
        super(message);
    }
}
