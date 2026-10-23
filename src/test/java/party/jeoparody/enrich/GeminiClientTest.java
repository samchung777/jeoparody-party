package party.jeoparody.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class GeminiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void joinsTextPartsAndSkipsThoughts() throws Exception {
        var reply = mapper.readTree("""
                {"candidates":[{"content":{"parts":[
                  {"text":"thinking out loud","thought":true},
                  {"text":"[{\\"id\\":1,"},
                  {"text":"\\"title\\":\\"x\\"}]"}]}}]}""");
        assertEquals("[{\"id\":1,\"title\":\"x\"}]", GeminiClient.extractText(reply));
    }

    @Test
    void emptyReplyIsAnError() throws Exception {
        var reply = mapper.readTree("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
        assertThrows(IOException.class, () -> GeminiClient.extractText(reply));
    }
}
