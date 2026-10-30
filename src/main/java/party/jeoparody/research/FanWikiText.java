package party.jeoparody.research;

import java.util.regex.Pattern;

/**
 * Turns MediaWiki wikitext (what fan wikis return; most don't offer Wikipedia's plain-text extracts) into plain
 * text with "== Heading ==" lines kept, so WikiText.cleanArticle can apply the same story-only section rules.
 * Pure string functions, unit-tested by FanWikiTextTest.
 */
public final class FanWikiText {

    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern REFS = Pattern.compile("(?is)<ref[^>/]*/>|<ref[^>]*>.*?</ref>");
    private static final Pattern INNER_TEMPLATE = Pattern.compile("\\{\\{[^{}]*\\}\\}");
    private static final Pattern INNER_TABLE = Pattern.compile("(?s)\\{\\|(?:(?!\\{\\|).)*?\\|\\}");
    private static final Pattern MEDIA_LINK = Pattern.compile("(?i)\\[\\[\\s*(?:file|image|category|media)\\s*:[^\\[\\]]*\\]\\]");
    private static final Pattern PIPED_LINK = Pattern.compile("\\[\\[(?!\\s*(?i:file|image|category|media)\\s*:)[^\\[\\]|]*\\|([^\\[\\]]*)\\]\\]");
    private static final Pattern PLAIN_LINK = Pattern.compile("\\[\\[(?!\\s*(?i:file|image|category|media)\\s*:)([^\\[\\]|]*)\\]\\]");
    private static final Pattern EXTERNAL_LABELED = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+\\s+([^\\]]+)\\]");
    private static final Pattern EXTERNAL_BARE = Pattern.compile("\\[(?:https?:)?//[^\\]]*\\]");
    private static final Pattern BOLD_ITALIC = Pattern.compile("'{2,}");
    private static final Pattern HTML_TAGS = Pattern.compile("(?i)</?[a-z][^>]*>");
    private static final Pattern MAGIC_WORDS = Pattern.compile("__[A-Z]+__");
    private static final Pattern LIST_MARKS = Pattern.compile("(?m)^[*#:;]+\\s*");
    private static final Pattern SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private FanWikiText() {
    }

    /** Plain text from wikitext: templates, tables, references, files and markup removed; link text kept. */
    public static String clean(String wikitext) {
        if (wikitext == null || wikitext.isBlank()) {
            return "";
        }
        String s = COMMENTS.matcher(wikitext).replaceAll("");
        s = REFS.matcher(s).replaceAll("");
        s = removeNested(s, INNER_TEMPLATE);
        s = removeNested(s, INNER_TABLE);
        // Links can nest inside file captions: resolve the innermost ones until nothing changes.
        String before;
        do {
            before = s;
            s = PIPED_LINK.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
            s = PLAIN_LINK.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
            s = MEDIA_LINK.matcher(s).replaceAll("");
        } while (!s.equals(before));
        s = EXTERNAL_LABELED.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
        s = EXTERNAL_BARE.matcher(s).replaceAll("");
        s = BOLD_ITALIC.matcher(s).replaceAll("");
        s = HTML_TAGS.matcher(s).replaceAll(" ");
        s = MAGIC_WORDS.matcher(s).replaceAll("");
        s = LIST_MARKS.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ");
        s = s.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));
        return BLANK_LINES.matcher(s).replaceAll("\n\n").strip();
    }

    private static String removeNested(String s, Pattern innermost) {
        String before;
        do {
            before = s;
            s = innermost.matcher(s).replaceAll("");
        } while (!s.equals(before));
        return s;
    }
}
