package com.betteranalytics.crm.security;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * Error bodies written directly by servlet filters (which run before Spring MVC's
 * exception handlers) — same {"error": "...", "code": "..."} shape as
 * GlobalExceptionHandler so clients only ever parse one format.
 */
public final class JsonErrors {

    private JsonErrors() {}

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        StringBuilder sb = new StringBuilder("{\"error\":").append(quote(message))
                .append(",\"code\":").append(quote(code));
        if (requestId != null) sb.append(",\"requestId\":").append(quote(requestId));
        sb.append('}');
        response.getWriter().write(sb.toString());
        response.flushBuffer();
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
