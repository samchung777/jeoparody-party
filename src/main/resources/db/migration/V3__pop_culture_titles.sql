-- Prompt versioning lets us re-tag clues whenever the Gemini prompt improves,
-- and each keyword gets a themed (often punny) category title for the board.
ALTER TABLE clue
    ADD COLUMN enrich_version INT NOT NULL DEFAULT 0;

-- Give every clue a fresh set of attempts under the new prompt version.
UPDATE clue SET enrich_attempts = 0;

ALTER TABLE keyword
    ADD COLUMN title VARCHAR(80) NULL;
