package party.jeoparody.game;

import java.util.List;

/** A board column: a voted keyword and its five clues (top row easiest). */
public record BoardCategory(KeywordOption keyword, List<BoardCell> cells) {
}
