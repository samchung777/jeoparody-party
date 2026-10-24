-- Hosts can remove a whole category (keyword) from future games. Its clues are kept: most also belong
-- to other keywords. Restorable from the trivia library page.
ALTER TABLE keyword
    ADD COLUMN retired_at   TIMESTAMP    NULL,
    ADD COLUMN retired_note VARCHAR(300) NULL;
