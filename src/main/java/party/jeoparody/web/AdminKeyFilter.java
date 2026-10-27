package party.jeoparody.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protects the admin API (imports, enrichment, Wikipedia topics, clue feedback review) with a shared key
 * sent as the {@code X-Admin-Key} header. When {@code app.admin-key} is blank (local development) the
 * admin API is open; on Cloud Run the key comes from Secret Manager.
 *
 * Game play is unaffected: the host is authorized per game by the secret host link.
 */
@Component
public class AdminKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Key";

    private final byte[] adminKey;

    /**
     * Reads app.admin-key: blank locally (admin API open for development); on Cloud Run application-cloud.yml sets it
     * from ADMIN_KEY, which Cloud Run injects from Secret Manager (admin-key:latest).
     */
    public AdminKeyFilter(@Value("${app.admin-key:}") String adminKey) {
        this.adminKey = adminKey == null ? new byte[0] : adminKey.strip().getBytes(StandardCharsets.UTF_8);
    }

    /** Skip the check for everything except /api/admin/**, or when no key is configured. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return adminKey.length == 0 || !request.getRequestURI().startsWith("/api/admin/");
    }

    /**
     * Compares keys in constant time (MessageDigest.isEqual) and answers 401 with a JSON message when they differ.
     * library.html's adminFetch asks for the key once and sends it on every admin call.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        byte[] given = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(adminKey, given)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"Admin key required\"}");
    }
}
