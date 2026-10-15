package com.betteranalytics.crm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Coarse per-client-IP ceiling across the whole API, so one misbehaving client
 * can't starve everyone else. Sign-in has its own much tighter limit
 * (AuthController) and Master API keys have a per-key limit (ApiKeyAuthFilter).
 * The client IP honours X-Forwarded-For only via Tomcat's RemoteIpValve
 * (server.forward-headers-strategy=native), i.e. only from trusted proxies.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final int perMinute;

    public RateLimitFilter(RateLimiter rateLimiter,
                           @Value("${app.rate-limit.per-ip-per-minute:1200}") int perMinute) {
        this.rateLimiter = rateLimiter;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        RateLimiter.Decision d = rateLimiter.hit("ip:" + request.getRemoteAddr(), perMinute, 60_000);
        if (!d.allowed()) {
            response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            JsonErrors.write(response, 429, "RATE_LIMITED", "Too many requests; please slow down.");
            return;
        }
        chain.doFilter(request, response);
    }
}
