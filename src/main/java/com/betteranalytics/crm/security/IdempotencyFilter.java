package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.ApiKey;
import com.betteranalytics.crm.entity.User;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Idempotency keys for POST / PATCH under /api/** (Stripe v1 semantics on the
 * IETF draft's {@code Idempotency-Key} header), for both the Master API and the
 * normal JWT API.
 *
 *  - The key (1–255 printable ASCII characters, at most one header) is scoped to
 *    the credential: the API key id, or the user id for JWT sessions.
 *  - The first request inserts an IN_PROGRESS row (unique constraint), runs, and
 *    stores status + body. A retry with the same key and the same request
 *    (SHA-256 of method + path + query + body) replays it with
 *    {@code Idempotent-Replayed: true}; the same key with a different request is
 *    422 IDEMPOTENCY_KEY_REUSED; a retry while the first is still running is
 *    409 IDEMPOTENCY_IN_PROGRESS.
 *  - Runs after authentication, rate limiting and authorization, so filter-level
 *    rejections never touch the store. Results that mean "never executed" — 400
 *    (validation), 401, 403, 422 and 429 — are not stored: the key is released and
 *    may be retried. Everything else, including 5xx, is stored (Stripe).
 *  - Only the outer request is handled: the Master API's forwarded sub-requests
 *    (proxy and batch) are not re-processed, so a batch is idempotent as a whole.
 *  - Keys live for 24 hours ({@link IdempotencyStore#TTL}).
 *  - Not supported (passed through without idempotency): multipart and form
 *    bodies, and responses that are not text (or over 1 MB) are not stored.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final int MAX_KEY_LENGTH = 255;
    static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;
    static final int MAX_STORED_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<Integer> NOT_STORED = Set.of(400, 401, 403, 422, 429);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
        String m = request.getMethod();
        if (!"POST".equals(m) && !"PATCH".equals(m)) return true;
        if (!request.getRequestURI().startsWith("/api/")) return true;
        return request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        if (values.size() != 1) {
            JsonErrors.write(response, 400, "IDEMPOTENCY_KEY_INVALID", "Send at most one Idempotency-Key header.");
            return;
        }
        String key = parseKey(values.get(0));
        if (key == null) {
            JsonErrors.write(response, 400, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 1-255 printable ASCII characters.");
            return;
        }
        String credential = credentialKey(request);
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (credential == null || contentType.startsWith("multipart/")
                || contentType.startsWith("application/x-www-form-urlencoded")) {
            chain.doFilter(request, response);   // unauthenticated or unsupported body: no idempotency
            return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            JsonErrors.write(response, 413, "IDEMPOTENCY_BODY_TOO_LARGE",
                    "Idempotency-Key is supported for request bodies up to 10 MB.");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            JsonErrors.write(response, 413, "IDEMPOTENCY_BODY_TOO_LARGE",
                    "Idempotency-Key is supported for request bodies up to 10 MB.");
            return;
        }
        String hash = requestHash(request.getMethod(), request.getRequestURI(), request.getQueryString(), body);

        IdempotencyStore.Begin begin = store.begin(credential, key, hash);
        switch (begin.kind()) {
            case MISMATCH -> {
                JsonErrors.write(response, 422, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
                return;
            }
            case IN_PROGRESS -> {
                response.setHeader("Retry-After", "1");
                JsonErrors.write(response, 409, "IDEMPOTENCY_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed; retry shortly.");
                return;
            }
            case REPLAY -> {
                replay(response, begin.stored());
                return;
            }
            case NEW -> { }
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            byte[] out = wrapped.getContentAsByteArray();
            String text = storableText(wrapped.getContentType(), out);
            if (!NOT_STORED.contains(status) && text != null) {
                store.complete(credential, key, status, wrapped.getContentType(), text);
                stored = true;
            }
        } finally {
            if (!stored) {
                try {
                    store.release(credential, key);
                } catch (Exception e) {
                    log.warn("Could not release idempotency key: {}", e.toString());
                }
            }
            wrapped.copyBodyToResponse();
        }
    }

    private static void replay(HttpServletResponse response, IdempotencyStore.Stored s) throws IOException {
        response.setStatus(s.status());
        response.setHeader(REPLAYED_HEADER, "true");
        if (s.contentType() != null) response.setContentType(s.contentType());
        byte[] bytes = s.body() == null ? new byte[0] : s.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        if (bytes.length > 0) response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    /** Body as text when it is safe to store and replay byte-for-byte; otherwise null. */
    static String storableText(String contentType, byte[] body) {
        if (body.length == 0) return "";
        if (body.length > MAX_STORED_RESPONSE_BYTES) return null;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean textual = ct.isEmpty() || ct.contains("json") || ct.startsWith("text/") || ct.contains("xml");
        if (!textual) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Accepts a bare token or an RFC 8941 sf-string ("..."); 1–255 printable ASCII characters. */
    static String parseKey(String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.length() >= 2 && k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length() - 1);
        if (k.isEmpty() || k.length() > MAX_KEY_LENGTH) return null;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c < 0x20 || c > 0x7e) return null;
        }
        return k;
    }

    static String credentialKey(HttpServletRequest request) {
        Object key = request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY);
        if (key instanceof ApiKey k && k.getId() != null) return "apikey:" + k.getId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u && u.getId() != null) return "user:" + u.getId();
        return null;
    }

    public static String requestHash(String method, String path, String query, byte[] body) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(method.getBytes(StandardCharsets.UTF_8));
            d.update((byte) '\n');
            d.update(path.getBytes(StandardCharsets.UTF_8));
            if (query != null) {
                d.update((byte) '?');
                d.update(query.getBytes(StandardCharsets.UTF_8));
            }
            d.update((byte) '\n');
            d.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Re-readable request body (the original stream was consumed for hashing). */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    enc != null ? java.nio.charset.Charset.forName(enc) : StandardCharsets.UTF_8));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
