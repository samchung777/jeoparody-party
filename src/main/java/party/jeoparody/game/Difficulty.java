package party.jeoparody.game;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The host's difficulty setting: which clue difficulty BoardBuilder tries first for each row of a column, and
 * for Final Jeopardy. Each row falls back to the next difficulty when a category runs out, so a category with
 * few easy clues still fills its column.
 */
public enum Difficulty {

    /** About four easy clues and one medium per column; Final is medium. */
    EASY("Easy", "Mostly easy clues, one or two medium",
            List.of(
                    List.of("easy", "medium", "hard"),
                    List.of("easy", "medium", "hard"),
                    List.of("easy", "medium", "hard"),
                    List.of("easy", "medium", "hard"),
                    List.of("medium", "easy", "hard")),
            List.of("medium", "easy", "hard")),

    /** Today's mix: easy at the top, hard at the bottom; Final is hard. */
    NORMAL("Normal", "Easy at the top, hard at the bottom",
            List.of(
                    List.of("easy", "medium", "hard"),
                    List.of("easy", "medium", "hard"),
                    List.of("medium", "easy", "hard"),
                    List.of("hard", "medium", "easy"),
                    List.of("hard", "medium", "easy")),
            List.of("hard", "medium", "easy")),

    /** About one medium clue and four hard per column; Final is hard. */
    HARD("Hard", "Mostly hard clues for superfans",
            List.of(
                    List.of("medium", "hard", "easy"),
                    List.of("hard", "medium", "easy"),
                    List.of("hard", "medium", "easy"),
                    List.of("hard", "medium", "easy"),
                    List.of("hard", "medium", "easy")),
            List.of("hard", "medium", "easy"));

    private final String label;
    private final String description;
    private final List<List<String>> rowPreferences;
    private final List<String> finalPreference;

    Difficulty(String label, String description, List<List<String>> rowPreferences, List<String> finalPreference) {
        this.label = label;
        this.description = description;
        this.rowPreferences = rowPreferences;
        this.finalPreference = finalPreference;
    }

    /** Shown in the host's picker and on the board ("Easy"). */
    public String label() {
        return label;
    }

    /** One line for the host's picker. */
    public String description() {
        return description;
    }

    /** Difficulties to try, in order, for one row (0 = top row). */
    public List<String> preferencesForRow(int row) {
        return rowPreferences.get(row);
    }

    /** Difficulties to try, in order, for the Final Jeopardy clue. */
    public List<String> finalPreference() {
        return finalPreference;
    }

    /** Case-insensitive lookup by label or name ("easy", "Hard"). */
    public static Optional<Difficulty> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.strip();
        return Arrays.stream(values()).filter(d -> d.label.equalsIgnoreCase(n) || d.name().equalsIgnoreCase(n))
                .findFirst();
    }
}
