-- Host feedback on clues. Retired clues are kept (not deleted) so a later re-import can't bring
-- them back (the question_hash unique key still matches) and so they can be restored.
ALTER TABLE clue
    ADD COLUMN retired_at    TIMESTAMP    NULL,
    ADD COLUMN feedback_note VARCHAR(300) NULL,   -- host's guidance for the next Gemini rewrite
    ADD KEY idx_clue_retired (retired_at);

CREATE TABLE clue_feedback (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    clue_id    BIGINT       NOT NULL,
    game_code  CHAR(4)      NULL,
    action     ENUM('remove', 'reword', 'restore') NOT NULL,
    reason     VARCHAR(32)  NOT NULL,
    note       VARCHAR(300) NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_clue_feedback_clue (clue_id),
    CONSTRAINT fk_clue_feedback_clue FOREIGN KEY (clue_id) REFERENCES clue (id) ON DELETE CASCADE
);
