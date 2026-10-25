package party.jeoparody.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminKeyFilterTest {

    private MockFilterChain run(String key, String uri, String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (header != null) {
            request.addHeader(AdminKeyFilter.HEADER, header);
        }
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AdminKeyFilter(key).doFilter(request, response, chain);
        lastStatus = response.getStatus();
        return chain;
    }

    private int lastStatus;

    @Test
    void openWhenNoKeyIsConfigured() throws Exception {
        assertNotNull(run("", "/api/admin/import", null).getRequest());
    }

    @Test
    void adminApiNeedsTheKey() throws Exception {
        assertNull(run("s3cret", "/api/admin/import", null).getRequest());
        assertEquals(401, lastStatus);
        assertNull(run("s3cret", "/api/admin/import", "wrong").getRequest());
        assertNotNull(run("s3cret", "/api/admin/import", "s3cret").getRequest());
    }

    @Test
    void gameplayAndPagesAreNotAffected() throws Exception {
        assertNotNull(run("s3cret", "/api/games", null).getRequest());
        assertNotNull(run("s3cret", "/library.html", null).getRequest());
        assertNotNull(run("s3cret", "/api/keywords", null).getRequest());
    }
}
