package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Reads "Authorization: Bearer <jwt>", verifies signature/issuer/expiry, and
 * loads the acting user (with their organizationId) into the security context.
 *
 * A token is only honoured while it still matches the user's current state:
 * same organization, account active, and the same tokenVersion it was issued
 * with — bumping tokenVersion (password change, deactivation, role change,
 * "sign out everywhere") revokes every outstanding token at once.
 *
 * Users flagged mustChangePassword can reach only the endpoints needed to
 * change it; everything else answers 403 PASSWORD_CHANGE_REQUIRED.
 *
 * Master API keys ("bcrm_…") are not JWTs and are handled by ApiKeyAuthFilter.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout-all", "/api/auth/logout");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            if (!token.startsWith(ApiKeyAuthFilter.KEY_PREFIX)) {
                User user = resolve(token);
                if (user != null) {
                    var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    if (user.isMustChangePassword()
                            && !PASSWORD_CHANGE_ALLOWED.contains(request.getRequestURI())) {
                        JsonErrors.write(response, 403, "PASSWORD_CHANGE_REQUIRED",
                                "You must change your password before continuing.");
                        return;
                    }
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private User resolve(String token) {
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = Long.parseLong(claims.getSubject());
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.isActive()) return null;

            Number orgId = claims.get("orgId", Number.class);
            if (orgId == null || orgId.longValue() != user.getOrganizationId()) return null;

            Number ver = claims.get("ver", Number.class);
            if (ver == null || ver.intValue() != user.getTokenVersion()) return null;
            return user;
        } catch (Exception invalidOrExpired) {
            return null; // leave unauthenticated → 401 from the entry point
        }
    }
}
