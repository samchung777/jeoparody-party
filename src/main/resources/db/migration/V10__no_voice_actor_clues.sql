-- Players don't want "who is the voice actor?" clues. Retire the obvious ones right away (logged like a host's
-- "Remove", so they show in the library's Flagged clues and can be restored). ReviewPrompt v2 then re-reviews
-- every live clue with Gemini and catches the rest, plus actor clues in anime, cartoon and game categories.
INSERT INTO clue_feedback (clue_id, action, reason, note)
SELECT id, 'remove', 'behind-the-scenes', 'voice actor clue'
FROM clue
WHERE retired_at IS NULL
  AND CONCAT_WS(' ', jeopardy_clue, jeopardy_response, question) REGEXP
      'voice (actor|actress|actors|cast)|voiced by|provides? the voice|provided the voice|lends? (his|her|their) voice|lent (his|her|their) voice|seiyuu|(actor|actress) (who )?voices|(actor|actress) who voiced';

UPDATE clue
SET retired_at = CURRENT_TIMESTAMP
WHERE retired_at IS NULL
  AND CONCAT_WS(' ', jeopardy_clue, jeopardy_response, question) REGEXP
      'voice (actor|actress|actors|cast)|voiced by|provides? the voice|provided the voice|lends? (his|her|their) voice|lent (his|her|their) voice|seiyuu|(actor|actress) (who )?voices|(actor|actress) who voiced';
