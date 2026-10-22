CREATE TABLE category (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    source     VARCHAR(32)  NOT NULL,
    source_id  INT          NOT NULL,
    name       VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_source (source, source_id)
);

CREATE TABLE clue (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    category_id   BIGINT       NOT NULL,
    difficulty    ENUM('easy', 'medium', 'hard') NOT NULL,
    question      TEXT         NOT NULL,
    answer        VARCHAR(512) NOT NULL,
    question_hash CHAR(64)     NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clue_question_hash (question_hash),
    KEY idx_clue_category_difficulty (category_id, difficulty),
    CONSTRAINT fk_clue_category FOREIGN KEY (category_id) REFERENCES category (id)
);
