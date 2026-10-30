-- Staleness: how many games have shown each clue. Boards prefer the least-shown clues, and a category whose
-- clues have all been shown twice or more is queued for research (more clues).
ALTER TABLE clue
    ADD COLUMN staleness INT NOT NULL DEFAULT 0;

-- General categories (Movies, TV, Anime & Manga, ...) per keyword, used by host themes and genre browsing.
CREATE TABLE keyword_genre (
    keyword_id BIGINT      NOT NULL,
    genre      VARCHAR(40) NOT NULL,
    source     ENUM('rule', 'gemini') NOT NULL,
    PRIMARY KEY (keyword_id, genre),
    KEY idx_keyword_genre_genre (genre),
    CONSTRAINT fk_keyword_genre_keyword FOREIGN KEY (keyword_id) REFERENCES keyword (id) ON DELETE CASCADE
);

-- Set once a keyword has been classified (even with no genre), so Gemini isn't asked about it again.
ALTER TABLE keyword
    ADD COLUMN genres_checked_at TIMESTAMP NULL;

-- Work for the research worker: more clues for a stale category, or a topic players suggested.
CREATE TABLE research_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    kind        ENUM('stale_category', 'suggestion', 'manual') NOT NULL,
    keyword_id  BIGINT       NULL,
    topic       VARCHAR(200) NOT NULL,
    status      ENUM('queued', 'running', 'done', 'failed', 'not_found') NOT NULL DEFAULT 'queued',
    sources     VARCHAR(200) NULL,
    source_url  VARCHAR(500) NULL,
    clues_added INT          NOT NULL DEFAULT 0,
    error       VARCHAR(500) NULL,
    game_code   CHAR(4)      NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_research_task_status (status, id),
    KEY idx_research_task_keyword (keyword_id, status),
    CONSTRAINT fk_research_task_keyword FOREIGN KEY (keyword_id) REFERENCES keyword (id) ON DELETE SET NULL
);

-- Topics players and hosts suggest during games. Checked for research when a game ends.
CREATE TABLE topic_suggestion (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    topic            VARCHAR(120) NOT NULL,
    game_code        CHAR(4)      NULL,
    suggested_by     VARCHAR(64)  NULL,
    status           ENUM('new', 'queued', 'done', 'not_found', 'rejected') NOT NULL DEFAULT 'new',
    research_task_id BIGINT       NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_topic_suggestion_status (status, id)
);
