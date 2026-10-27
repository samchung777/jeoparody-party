package party.jeoparody.trivia;

import java.time.Instant;

/** Snapshot of the background trivia import, returned by GET /api/admin/import. */
public record ImportStatus(
        boolean running,
        int categoriesTotal,
        int categoriesDone,
        int cluesAdded,
        String lastError,
        Instant finishedAt) {

    /** Immutable snapshots: each update returns a new record, read safely from web threads. */
    static ImportStatus idle() {
        return new ImportStatus(false, 0, 0, 0, null, null);
    }

    /** State changes used by TriviaImportService: started, categoryDone, withError, finished. */
    static ImportStatus started(int categoriesTotal) {
        return new ImportStatus(true, categoriesTotal, 0, 0, null, null);
    }

    ImportStatus categoryDone(int added) {
        return new ImportStatus(running, categoriesTotal, categoriesDone + 1, cluesAdded + added, lastError, finishedAt);
    }

    ImportStatus withError(String error) {
        return new ImportStatus(running, categoriesTotal, categoriesDone, cluesAdded, error, finishedAt);
    }

    ImportStatus finished() {
        return new ImportStatus(false, categoriesTotal, categoriesDone, cluesAdded, lastError, Instant.now());
    }
}
