package com.betteranalytics.crm.config;

import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import com.betteranalytics.crm.security.IdempotencyFilter;
import com.betteranalytics.crm.security.JsonErrors;
import com.betteranalytics.crm.security.JwtAuthFilter;
import com.betteranalytics.crm.security.RateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final List<String> allowedOrigins;
    private IdempotencyFilter idempotencyFilter;

    /** Idempotency-Key handling runs after authentication and authorization (see IdempotencyFilter). */
    @org.springframework.beans.factory.annotation.Autowired
    void setIdempotencyFilter(IdempotencyFilter idempotencyFilter) {
        this.idempotencyFilter = idempotencyFilter;
    }

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter,
                          RateLimitFilter rateLimitFilter,
                          @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless bearer-token API: no cookies are used for auth, so CSRF does not apply.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/health", "/error").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Meta calls the WhatsApp webhook without a JWT; it is HMAC-verified instead.
                .requestMatchers("/api/whatsapp/webhook").permitAll()
                // Automation webhook triggers are authenticated by a per-automation HMAC signature.
                .requestMatchers(HttpMethod.POST, "/api/automations/*/hook").permitAll()
                // Email unsubscribe links carry a signed token; Event Grid SMS events a shared secret.
                .requestMatchers("/api/email/unsubscribe").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/sms/events").permitAll()
                // Public, read-only API reference (no data) — disable via API_DOCS_ENABLED=false.
                .requestMatchers("/api/docs", "/api/docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                        JsonErrors.write(res, 401, "UNAUTHENTICATED", "Authentication required."))
                .accessDeniedHandler((req, res, e) ->
                        JsonErrors.write(res, 403, "FORBIDDEN", "You don't have permission to do that."))
            )
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentTypeOptions(c -> {})
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=()"))
                // JSON API responses never need to load anything; Swagger UI is excluded.
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(new AntPathRequestMatcher("/api/docs/**")),
                        new StaticHeadersWriter("Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; sandbox")))
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(idempotencyFilter, AuthorizationFilter.class);
        return http.build();
    }

    /**
     * StrictHttpFirewall rejects malformed or suspicious URLs ("..", encoded
     * slashes, ";" path parameters, ...) before any filter runs. Spring's default
     * handler answers with a bare 400/500 from the servlet container; this one
     * answers with the same {"error","code","requestId"} JSON as every other
     * error, and never echoes the offending URL back.
     */
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return (request, response, ex) ->
                JsonErrors.write(response, 400, "REQUEST_REJECTED", "The request URL was rejected as malformed.");
    }

    /**
     * Default: no cross-origin access at all — the SPA is served from the same
     * origin and proxies /api. Set CORS_ALLOWED_ORIGINS (comma-separated) only if
     * the frontend or another browser client lives on a different origin.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins.isEmpty()) return source;
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "X-Request-Id",
                "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "Retry-After", "Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // These filters run inside the Spring Security chain only — stop Spring Boot
    // from also auto-registering them as plain servlet filters.
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter f) {
        FilterRegistrationBean<JwtAuthFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter f) {
        FilterRegistrationBean<ApiKeyAuthFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter f) {
        FilterRegistrationBean<RateLimitFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter f) {
        FilterRegistrationBean<IdempotencyFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }
}
