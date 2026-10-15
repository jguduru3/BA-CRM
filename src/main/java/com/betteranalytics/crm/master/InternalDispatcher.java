package com.betteranalytics.crm.master;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Executes an API call in-process by forwarding a synthetic request through
 * the servlet container, capturing the response instead of writing it.
 *
 * Because the call goes through the real DispatcherServlet with the caller's
 * SecurityContext still bound to the thread, every controller, validation rule,
 * PermissionService check, audit entry and webhook fires exactly as if the
 * client had called the endpoint directly — the Master API adds no second,
 * divergent implementation of any business rule.
 */
@Component
public class InternalDispatcher {

    public record Result(int status, String contentType, byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * @param path        absolute target path including the /api prefix and an
     *                    optional query string (already URL-encoded)
     * @param jsonBody    request body (JSON) or null
     */
    public Result execute(HttpServletRequest outer, String method, String path, byte[] jsonBody)
            throws ServletException, IOException {
        SubRequest req = new SubRequest(outer, method, jsonBody);
        CapturingResponse res = new CapturingResponse(new NullResponse());
        RequestDispatcher dispatcher = outer.getRequestDispatcher(path);
        if (dispatcher == null) throw new IllegalArgumentException("unroutable path");
        dispatcher.forward(req, res);
        return new Result(res.status, res.getContentType(), res.buffer.toByteArray());
    }

    // ── synthetic request ───────────────────────────────────────────────────
    static final class SubRequest extends HttpServletRequestWrapper {
        private final String method;
        private final byte[] body;

        SubRequest(HttpServletRequest outer, String method, byte[] body) {
            super(outer);
            this.method = method;
            this.body = body == null ? new byte[0] : body;
        }

        @Override public String getMethod() { return method; }
        @Override public String getContentType() { return body.length > 0 ? "application/json" : null; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public String getCharacterEncoding() { return "UTF-8"; }

        @Override
        public String getHeader(String name) {
            if ("content-type".equalsIgnoreCase(name)) return getContentType();
            if ("content-length".equalsIgnoreCase(name)) return String.valueOf(body.length);
            if (isHidden(name)) return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String v = getHeader(name);
            if ("content-type".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name) || isHidden(name)) {
                return Collections.enumeration(v == null ? List.of() : List.of(v));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> e = super.getHeaderNames();
            while (e != null && e.hasMoreElements()) {
                String n = e.nextElement();
                if (!isHidden(n) && !"content-type".equalsIgnoreCase(n) && !"content-length".equalsIgnoreCase(n)) names.add(n);
            }
            if (body.length > 0) names.add("Content-Type");
            names.add("Content-Length");
            return Collections.enumeration(names);
        }

        private static boolean isHidden(String name) {
            // The outer batch request's own transfer headers don't describe the sub-request.
            return "transfer-encoding".equalsIgnoreCase(name) || "content-encoding".equalsIgnoreCase(name);
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
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }

    // ── captured response ──────────────────────────────────────────────────
    static final class CapturingResponse extends HttpServletResponseWrapper {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int status = 200;
        private String contentType;
        private PrintWriter writer;
        private ServletOutputStream stream;

        CapturingResponse(HttpServletResponse delegate) { super(delegate); }

        @Override public void setStatus(int sc) { status = sc; }
        @Override public int getStatus() { return status; }
        @Override public void sendError(int sc) { status = sc; }
        @Override public void sendError(int sc, String msg) { status = sc; }
        @Override public void sendRedirect(String location) { status = 302; }
        @Override public void setContentType(String type) { contentType = type; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() { buffer.reset(); status = 200; }
        @Override public void resetBuffer() { buffer.reset(); }
        @Override public void flushBuffer() {}
        @Override public void setHeader(String name, String value) { if ("content-type".equalsIgnoreCase(name)) contentType = value; }
        @Override public void addHeader(String name, String value) { setHeader(name, value); }

        @Override
        public ServletOutputStream getOutputStream() {
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener l) {}
                    @Override public void write(int b) { buffer.write(b); }
                    @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
            return writer;
        }
    }

    /** A do-nothing base response so nothing a sub-call does can touch the real one. */
    static final class NullResponse extends HttpServletResponseWrapper {
        NullResponse() {
            super((HttpServletResponse) java.lang.reflect.Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                    (p, m, a) -> {
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 200;
                        if (r == long.class) return 0L;
                        if (r == Collection.class) return List.of();
                        if (r == String.class) return null;
                        if (r == Locale.class) return Locale.ROOT;
                        return null;
                    }));
        }
    }
}
