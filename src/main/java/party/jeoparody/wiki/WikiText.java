package party.jeoparody.wiki;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text helpers for turning Wikipedia articles into trivia. No I/O, so every rule is unit-tested.
 */
public final class WikiText {

    /**
     * A parsed topic line:
     * {@code Keyword = Exact Wikipedia page [Group Keyword, Another] {12: note for the writer}}.
     * Only the keyword is required. Groups add the clues to broader categories too ("Shonen Anime");
     * the note steers the clue writer; the number overrides how many clues to ask for.
     */
    public record TopicSpec(String keyword, String pageQuery, List<String> groups, String focus, Integer count) {
    }

    /** Sections that never contain good trivia. Everything from the first of these onward is dropped. */
    private static final Pattern TRAILING_SECTIONS = Pattern.compile(
            "(?im)^==+\\s*(see also|references|notes|external links|further reading|bibliography|citations|"
                    + "sources|footnotes|works cited|explanatory notes)\\s*==+\\s*$");

    /** Top-level sections about how something was made or how it sold, not what it is about. */
    private static final Pattern BEHIND_THE_SCENES = Pattern.compile(
            "^(development|production|productions|design|release|releases|reception|critical|box office|commercial|"
                    + "sales|marketing|legacy|accolades|awards|honou?rs|impact|influence|controvers|background|"
                    + "conception|creation|writing|casting|filming|music|soundtrack|score|recording|cast recording|"
                    + "adaptation|other media|media|merchandise|technical|locali[sz]ation|publication|broadcast|"
                    + "home media|future|sequel|related works|in popular culture|cultural|analysis|themes|style|"
                    + "historicity|authorship|dating|date|composition|textual|canon|manuscript|translation|"
                    + "criticism|scholarship|history of|revival|tour|staging|personnel|track listing|charts|"
                    + "certifications|business|esports|competitive play|ratings|viewership|distribution)\\b");
    private static final Pattern LEVEL2_HEADING = Pattern.compile("^==(?!=)\\s*(.*?)\\s*==\\s*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^(==+)\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern FOCUS = Pattern.compile("\\{\\s*(?:(\\d{1,2})\\s*:?)?\\s*([^}]*)\\}");
    private static final Pattern GROUPS = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern KEYWORD_JUNK = Pattern.compile("[^\\p{L}\\p{N} &'\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final List<String> ARTICLES = List.of("the ", "a ", "an ");
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "of", "and", "to", "in", "on", "x");
    static final int MAX_KEYWORD_LENGTH = 40;
    /** If dropping behind-the-scenes sections leaves less than this, the whole article is used instead. */
    static final int MIN_STORY_CHARS = 1500;

    private WikiText() {
    }

    /**
     * Parses what the host typed.
     * <ul>
     *   <li>"Hamilton (musical)" -> keyword "Hamilton", page "Hamilton (musical)"</li>
     *   <li>"Harvest Moon = Harvest Moon (video game series)" -> that exact page</li>
     *   <li>"Naruto [Shonen Anime]" -> clues also count toward a "Shonen Anime" category</li>
     *   <li>"Disney Songs = Let It Go (Disney song) {8: who sings it and in which film}"</li>
     * </ul>
     */
    public static TopicSpec parseTopic(String raw) {
        String text = raw == null ? "" : raw;
        String focus = null;
        Integer count = null;
        Matcher f = FOCUS.matcher(text);
        if (f.find()) {
            count = f.group(1) == null ? null : Integer.valueOf(f.group(1));
            focus = collapse(f.group(2));
            focus = focus.isEmpty() ? null : focus;
            text = text.substring(0, f.start()) + text.substring(f.end());
        }
        List<String> groups = new ArrayList<>();
        Matcher g = GROUPS.matcher(text);
        if (g.find()) {
            Arrays.stream(g.group(1).split(",")).map(WikiText::keyword).filter(k -> !k.isEmpty()).forEach(groups::add);
            text = text.substring(0, g.start()) + text.substring(g.end());
        }
        text = collapse(text);
        String keywordPart = text;
        String page = text;
        int eq = text.indexOf('=');
        if (eq > 0) {
            keywordPart = text.substring(0, eq).trim();
            page = text.substring(eq + 1).trim();
            if (page.isEmpty()) {
                page = keywordPart;
            }
        }
        String keyword = keyword(PARENTHETICAL.matcher(keywordPart).replaceAll(""));
        groups.removeIf(grp -> answerKey(grp).equals(answerKey(keyword)));
        return new TopicSpec(keyword, page, List.copyOf(new LinkedHashSet<>(groups)), focus, count);
    }

    /** Cleans a keyword the same way Gemini's keywords are cleaned: letters, digits, spaces, & ' - only. */
    public static String keyword(String raw) {
        String k = collapse(KEYWORD_JUNK.matcher(raw == null ? "" : raw).replaceAll(" "));
        return k.length() <= MAX_KEYWORD_LENGTH ? k : k.substring(0, MAX_KEYWORD_LENGTH).trim();
    }

    /** Same as {@link #cleanArticle(String, int, boolean)} keeping every section. */
    public static String cleanArticle(String extract, int maxChars) {
        return cleanArticle(extract, maxChars, false);
    }

    /**
     * Turns a TextExtracts plain-text article (exsectionformat=wiki) into prompt-friendly text:
     * drops reference/link sections and, if {@code storyOnly}, behind-the-scenes sections (development,
     * release, reception...); turns "== Heading ==" into "Heading:"; trims to maxChars at a sentence.
     */
    public static String cleanArticle(String extract, int maxChars, boolean storyOnly) {
        if (extract == null) {
            return "";
        }
        String text = extract.replace("\r", "");
        Matcher tail = TRAILING_SECTIONS.matcher(text);
        if (tail.find()) {
            text = text.substring(0, tail.start());
        }
        if (storyOnly) {
            String story = dropBehindTheScenes(text);
            if (story.strip().length() >= MIN_STORY_CHARS) {
                text = story;
            }
        }
        text = HEADING.matcher(text).replaceAll("\n$2:");
        text = text.replaceAll("\n{3,}", "\n\n").trim();
        return truncateAtSentence(text, maxChars);
    }

    /** Removes top-level sections (and their subsections) whose heading is about making/selling the work. */
    static String dropBehindTheScenes(String text) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : text.split("\n", -1)) {
            Matcher m = LEVEL2_HEADING.matcher(line);
            if (m.matches()) {
                skipping = BEHIND_THE_SCENES.matcher(m.group(1).toLowerCase(Locale.ROOT)).find();
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    public static String truncateAtSentence(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(". ", maxChars);
        return text.substring(0, cut > maxChars / 2 ? cut + 1 : maxChars);
    }

    /**
     * True if a search result's title plausibly is the page the host asked for: every meaningful word of the
     * query (ignoring a trailing "(disambiguation)", case and accents) appears in the title. Stops a search for
     * "Super Powereds" from landing on "Super Mario".
     */
    public static boolean titleMatchesQuery(String title, String query) {
        Set<String> titleWords = words(title);
        Set<String> queryWords = words(PARENTHETICAL.matcher(query).replaceAll(""));
        queryWords.removeAll(STOP_WORDS);
        return !queryWords.isEmpty() && titleWords.containsAll(queryWords);
    }

    private static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(s).replaceAll("[^\\p{L}\\p{N}' ]", " ").split(" ")) {
            String word = w.replace("'s", "").replace("'", "");
            if (!word.isBlank()) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * Normalizes text for comparing a quoted sentence with the article: case, accents, curly quotes,
     * dashes and whitespace don't matter.
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFKD);
        t = MARKS.matcher(t).replaceAll("")
                .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-').replace(' ', ' ')
                .toLowerCase(Locale.ROOT);
        return collapse(t);
    }

    /** True if the quote (ignoring surrounding quotes, ellipses and final punctuation) is in the article. */
    public static boolean quoteAppearsIn(String quote, String normalizedArticle, int minLength) {
        String q = normalize(quote);
        q = q.replaceAll("^[\"'.…\\s]+", "").replaceAll("[\"'.…\\s]+$", "");
        return q.length() >= minLength && normalizedArticle.contains(q);
    }

    /** True if the answer is spelled out inside the clue (a giveaway). Very short answers are ignored. */
    public static boolean givesAwayAnswer(String clue, String answer) {
        String a = stripArticle(normalize(answer));
        if (a.length() < 4) {
            return false;
        }
        return (" " + normalize(clue) + " ").contains(a);
    }

    /** Lower-case answer without accents or a leading "the/a/an", for duplicate and topic comparisons. */
    public static String answerKey(String answer) {
        return stripArticle(normalize(answer)).replaceAll("[^\\p{L}\\p{N} ]", "").trim();
    }

    private static String stripArticle(String s) {
        for (String article : ARTICLES) {
            if (s.startsWith(article)) {
                return s.substring(article.length());
            }
        }
        return s;
    }

    private static String collapse(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll(" ").trim();
    }
}
