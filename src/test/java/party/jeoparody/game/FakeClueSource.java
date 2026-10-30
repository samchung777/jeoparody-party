package party.jeoparody.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory trivia for game tests: N keywords, each with 2 easy, 2 medium and 3 hard clues. */
final class FakeClueSource implements ClueSource {

    final List<KeywordOption> keywords = new ArrayList<>();
    final Map<Long, List<CandidateClue>> clues = new HashMap<>();

    FakeClueSource(int keywordCount) {
        long clueId = 1;
        for (long k = 1; k <= keywordCount; k++) {
            // Genres rotate Movies / TV / Anime & Manga so theme and browse tests have known counts.
            String genre = List.of("Movies", "TV", "Anime & Manga").get((int) (k % 3));
            keywords.add(new KeywordOption(k, "Keyword " + k, k % 3 == 0 ? null : "Punny Title " + k, 7, Set.of(genre)));
            List<CandidateClue> list = new ArrayList<>();
            for (String difficulty : List.of("easy", "easy", "medium", "medium", "hard", "hard", "hard")) {
                list.add(new CandidateClue(clueId, difficulty, "Clue text " + clueId, "What is " + clueId + "?"));
                clueId++;
            }
            clues.put(k, list);
        }
    }

    @Override
    public List<KeywordOption> eligibleKeywords(int minClues) {
        return keywords;
    }

    @Override
    public List<CandidateClue> cluesForKeyword(long keywordId) {
        return clues.getOrDefault(keywordId, List.of());
    }
}
