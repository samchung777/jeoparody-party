package party.jeoparody.game;

import java.util.List;

/** Where the game gets keywords and clues from. Backed by MySQL in the app, faked in tests. */
public interface ClueSource {

    /** Keywords with at least {@code minClues} Jeopardy-ready clues. */
    List<KeywordOption> eligibleKeywords(int minClues);

    /** All Jeopardy-ready clues tagged with the keyword. */
    List<CandidateClue> cluesForKeyword(long keywordId);
}
