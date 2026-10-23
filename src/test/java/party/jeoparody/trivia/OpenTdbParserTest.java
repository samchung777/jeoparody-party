package party.jeoparody.trivia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenTdbParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Builds an Open Trivia DB result the way the API sends it with encode=url3986. */
    private static String result(String type, String difficulty, String category, String question, String answer) {
        return """
                {"type":"%s","difficulty":"%s","category":"%s","question":"%s","correct_answer":"%s",
                 "incorrect_answers":["x","y","z"]}""".formatted(type, difficulty, category, question, answer);
    }

    private JsonNode response(String... results) throws Exception {
        return mapper.readTree("{\"response_code\":0,\"results\":[" + String.join(",", results) + "]}");
    }

    @Test
    void decodesUrlEncodedQuestions() throws Exception {
        JsonNode root = response(result("multiple", "easy", "Entertainment%3A%20Film",
                "Who%20directed%20%22Jaws%22%20%281975%29%3F", "Steven%20Spielberg"));
        List<ParsedQuestion> parsed = OpenTdbParser.parseQuestions(root);
        assertEquals(1, parsed.size());
        assertEquals("Who directed \"Jaws\" (1975)?", parsed.get(0).question());
        assertEquals("Steven Spielberg", parsed.get(0).answer());
        assertEquals("Entertainment: Film", parsed.get(0).category());
        assertEquals("easy", parsed.get(0).difficulty());
    }

    @Test
    void skipsQuestionsThatOnlyWorkWithMultipleChoiceOptions() throws Exception {
        JsonNode root = response(
                result("multiple", "easy", "Music", "Which%20of%20the%20following%20is%20a%20Beatle%3F", "Ringo"),
                result("multiple", "hard", "Music", "What%20one%20of%20these%20is%20loud%3F", "Drums"),
                result("multiple", "medium", "Music", "Who%20sang%20%22Thriller%22%3F", "Michael%20Jackson"));
        List<ParsedQuestion> parsed = OpenTdbParser.parseQuestions(root);
        assertEquals(1, parsed.size());
        assertEquals("Michael Jackson", parsed.get(0).answer());
    }

    @Test
    void skipsTrueFalseAndMalformedEntries() throws Exception {
        JsonNode root = response(
                result("boolean", "easy", "Film", "Is%20this%20true%3F", "True"),
                result("multiple", "impossible", "Film", "Odd%20difficulty%3F", "Yes"),
                result("multiple", "easy", "Film", "", "Empty%20question"));
        assertTrue(OpenTdbParser.parseQuestions(root).isEmpty());
    }

    @Test
    void readsResponseCodes() throws Exception {
        assertEquals(OpenTdbParser.RATE_LIMITED, OpenTdbParser.responseCode(mapper.readTree("{\"response_code\":5}")));
        assertEquals(-1, OpenTdbParser.responseCode(mapper.readTree("{}")));
    }

    @Test
    void parsesCategoryList() throws Exception {
        JsonNode root = mapper.readTree("""
                {"trivia_categories":[{"id":11,"name":"Entertainment: Film"},{"id":12,"name":"Entertainment: Music"}]}""");
        assertEquals(Map.of(11, "Entertainment: Film", 12, "Entertainment: Music"), OpenTdbParser.parseCategories(root));
    }

    @Test
    void displayNameDropsThePrefix() {
        assertEquals("Video Games", OpenTdbParser.displayName("Entertainment: Video Games"));
        assertEquals("Geography", OpenTdbParser.displayName("Geography"));
    }
}
