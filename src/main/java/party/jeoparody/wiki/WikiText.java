package party.jeoparody.wiki;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text helpers for turning Wikipedia articles into trivia. No I/O, so every rule is unit-tested.
 */
public final class WikiText {

    /** "Keyword = Exact page title" lets the host pick the right article for ambiguous names. */
    public record TopicSpec(String keyword, String pageQuery) {
    }

    /** Sections that never contain good trivia. Everything from the first of these onward is dropped. */
    private static final Pattern TRAILING_SECTIONS = Pattern.compile(
            "(?im)^==+\\s*(see also|references|notes|external links|further reading|bibliography|citations|"
                    + "sources|footnotes|works cited|explanatory notes)\\s*==+\\s*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^(==+)\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern KEYWORD_JUNK = Pattern.compile("[^\\p{L}\\p{N} &'\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final List<String> ARTICLES = List.of("the ", "a ", "an ");
    static final int MAX_KEYWORD_LENGTH = 40;

    private WikiText() {
    }

    /**
     * Parses what the host typed. "Hamilton (musical)" -> keyword "Hamilton", page "Hamilton (musical)".
     * "Harvest Moon = Harvest Moon (video game series)" -> keyword "Harvest Moon", that exact page.
     */
    public static TopicSpec parseTopic(String raw) {
        String text = collapse(raw);
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
        return new TopicSpec(keyword(PARENTHETICAL.matcher(keywordPart).replaceAll("")), page);
    }

    /** Cleans a keyword the same way Gemini's keywords are cleaned: letters, digits, spaces, & ' - only. */
    public static String keyword(String raw) {
        String k = collapse(KEYWORD_JUNK.matcher(raw == null ? "" : raw).replaceAll(" "));
        return k.length() <= MAX_KEYWORD_LENGTH ? k : k.substring(0, MAX_KEYWORD_LENGTH).trim();
    }

    /**
     * Turns a TextExtracts plain-text article (exsectionformat=wiki) into prompt-friendly text:
     * drops reference/link sections, turns "== Heading ==" into "Heading:", trims to maxChars at a
     * sentence boundary.
     */
    public static String cleanArticle(String extract, int maxChars) {
        if (extract == null) {
            return "";
        }
        String text = extract.replace("\r", "");
        Matcher tail = TRAILING_SECTIONS.matcher(text);
        if (tail.find()) {
            text = text.substring(0, tail.start());
        }
        text = HEADING.matcher(text).replaceAll("\n$2:");
        text = text.replaceAll("\n{3,}", "\n\n").trim();
        if (text.length() > maxChars) {
            int cut = text.lastIndexOf(". ", maxChars);
            text = text.substring(0, cut > maxChars / 2 ? cut + 1 : maxChars);
        }
        return text;
    }

    /**
     * Normalizes text for comparing a quoted sentence with the article: case, accents, curly quotes,
     * dashes and whitespace don't matter.
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC)
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

    /** Lower-case answer without a leading "the/a/an", for duplicate and topic comparisons. */
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
