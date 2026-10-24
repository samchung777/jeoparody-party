-- Clues written from Wikipedia articles keep their source and the sentence that proves them.
ALTER TABLE clue
    ADD COLUMN source_url   VARCHAR(500) NULL,
    ADD COLUMN source_quote TEXT         NULL;

-- Topics queued for clue generation ("Fire Emblem", "Hamilton (musical)", ...).
CREATE TABLE wiki_topic (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(200) NOT NULL,
    status          ENUM('queued', 'done', 'failed', 'not_found') NOT NULL DEFAULT 'queued',
    page_title      VARCHAR(300) NULL,
    page_url        VARCHAR(500) NULL,
    clues_generated INT          NOT NULL DEFAULT 0,
    clues_added     INT          NOT NULL DEFAULT 0,
    error           VARCHAR(500) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wiki_topic (topic),
    KEY idx_wiki_topic_status (status, id)
);
