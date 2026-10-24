-- Story-focused clues: a review pass retires behind-the-scenes clues (directors, release dates...),
-- and Wikipedia topics written with the old prompt are regenerated.
ALTER TABLE clue
    ADD COLUMN review_version INT NOT NULL DEFAULT 0,
    ADD KEY idx_clue_review (retired_at, review_version);

ALTER TABLE wiki_topic
    ADD COLUMN prompt_version INT NOT NULL DEFAULT 1;   -- topics generated so far used prompt v1
