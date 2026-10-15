package com.betteranalytics.crm.master;

import com.betteranalytics.crm.entity.ApiKey;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.WebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Master API — one stable, versioned entry point that exposes every CRM
 * endpoint to other platforms:
 *
 *   GET  /api/master/v1              catalogue of every resource/operation (self-describing)
 *   GET  /api/master/v1/me           who the credential acts as, and its scope
 *   GET  /api/master/v1/events       webhook event names
 *   POST /api/master/v1/batch        up to 50 operations in one call, optionally atomic
 *   ANY  /api/master/v1/{path...}    proxied to /api/{path...} (e.g. /api/master/v1/leads/42)
 *
 * Authentication: an API key (X-API-Key or "Authorization: Bearer bcrm_…",
 * created under Settings → Integrations) or a normal user JWT. Every call runs
 * as the key's acting user through the real controllers, so tenant isolation,
 * role permissions, field masking, auditing and webhooks behave identically.
 * Read-only keys may only issue GET requests.
 */
@RestController
@RequestMapping("/api/master")
public class MasterApiController {

    public static final String VERSION = "1";
    private static final String BASE = "/api/master/v1";
    private static final int MAX_BATCH = 50;
    private static final Pattern SAFE_PATH = Pattern.compile("^(/[A-Za-z0-9_~.\\-]+)+/?$");
    private static final Pattern REF = Pattern.compile("\\{\\{([A-Za-z0-9_-]+)\\.body((?:\\.[A-Za-z0-9_]+)*)}}");
    private static final Pattern QUOTED_REF = Pattern.compile("\"\\{\\{([A-Za-z0-9_-]+)\\.body((?:\\.[A-Za-z0-9_]+)*)}}\"");

    /** Never reachable through the gateway: auth/session, the gateway itself, credential management. */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "/auth", "/master", "/integrations", "/whatsapp/webhook", "/health");

    /** Additionally blocked for API keys: endpoints that read or change third-party secrets or passwords. */
    private static final List<Pattern> BLOCKED_FOR_KEYS = List.of(
            Pattern.compile("^/users/[^/]+/reset-password/?$"),
            Pattern.compile("^/settings(/.*)?$"),
            Pattern.compile("^/whatsapp/accounts(/.*)?$"),
            Pattern.compile("^/ai-automations/email-accounts(/.*)?$"));

    private final InternalDispatcher dispatcher;
    private final RequestMappingHandlerMapping handlerMapping;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;

    public MasterApiController(InternalDispatcher dispatcher,
                               @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
                               TransactionTemplate transactionTemplate, ObjectMapper objectMapper,
                               CurrentUser currentUser) {
        this.dispatcher = dispatcher;
        this.handlerMapping = handlerMapping;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
    }

    // ── discovery ────────────────────────────────────────────────────────────

    @GetMapping({"", "/", "/v1", "/v1/"})
    public Map<String, Object> catalog() {
        Map<String, List<Map<String, Object>>> resources = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = info.getPatternValues();
            Set<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) methods.add("GET");
            for (String p : patterns) {
                if (!p.startsWith("/api/") || p.startsWith("/api/master")) continue;
                String rest = p.substring(4);
                if (isBlocked(rest)) continue;
                String resource = rest.split("/")[1];
                for (String m : methods) {
                    Map<String, Object> op = new LinkedHashMap<>();
                    op.put("method", m);
                    op.put("path", BASE + rest);
                    op.put("operation", e.getValue().getBeanType().getSimpleName().replace("Controller", "")
                            + "." + e.getValue().getMethod().getName());
                    op.put("apiKeyAllowed", !blockedForKeys(rest));
                    op.put("requiresWriteScope", !m.equals("GET"));
                    resources.computeIfAbsent(resource, k -> new ArrayList<>()).add(op);
                }
            }
        }
        resources.values().forEach(list -> list.sort(Comparator
                .comparing((Map<String, Object> o) -> (String) o.get("path"))
                .thenComparing(o -> (String) o.get("method"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "Better CRM Master API");
        out.put("version", VERSION);
        out.put("baseUrl", BASE);
        out.put("authentication", Map.of(
                "apiKey", "Send 'X-API-Key: bcrm_…' or 'Authorization: Bearer bcrm_…'. Create keys in Settings → Integrations.",
                "scopes", Map.of("read", "GET only", "read_write", "all methods"),
                "jwt", "A signed-in user's bearer token is also accepted."));
        out.put("conventions", Map.of(
                "errors", "{\"error\": string, \"code\": string, \"requestId\": string}",
                "rateLimit", "per API key per minute; see X-RateLimit-Limit / X-RateLimit-Remaining / Retry-After",
                "tracing", "send X-Request-Id to correlate; it is echoed on every response",
                "batch", "POST " + BASE + "/batch — reference earlier results with {{opId.body.field}}"));
        out.put("openApi", "/api/docs/openapi.json");
        out.put("webhookEvents", WebhookService.EVENTS);
        out.put("operationCount", resources.values().stream().mapToInt(List::size).sum());
        out.put("resources", resources);
        return out;
    }

    @GetMapping("/v1/me")
    public Map<String, Object> me(HttpServletRequest request) {
        User user = currentUser.get();
        ApiKey key = (ApiKey) request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizationId", user.getOrganizationId());
        out.put("actingUser", Map.of("id", user.getId(), "name", user.getName(),
                "email", user.getEmail(), "role", user.getRole().name()));
        out.put("credential", key == null ? "user_session" : "api_key");
        if (key != null) {
            out.put("apiKey", Map.of("id", key.getId(), "name", key.getName(),
                    "prefix", key.getKeyPrefix(), "scope", key.getScope()));
        }
        out.put("scope", key == null || key.writeAllowed() ? "read_write" : "read");
        return out;
    }

    @GetMapping("/v1/events")
    public Map<String, Object> events() {
        return Map.of("events", WebhookService.EVENTS,
                "signature", "X-BetterCRM-Signature: sha256=HMAC_SHA256(secret, X-BetterCRM-Timestamp + \".\" + rawBody)");
    }

    // ── batch ───────────────────────────────────────────────────────────────

    /**
     * Body: {"atomic": false, "stopOnError": false, "operations": [
     *          {"id": "c", "method": "POST", "path": "/companies", "body": {...}},
     *          {"id": "p", "method": "POST", "path": "/contacts", "body": {"companyId": "{{c.body.id}}"}}]}
     *
     * atomic=true runs every operation in one database transaction and rolls all
     * of them back if any returns a 4xx/5xx (webhooks for rolled-back changes are
     * never sent). Otherwise operations run in order and each result stands alone.
     */
    @PostMapping("/v1/batch")
    public ResponseEntity<?> batch(@RequestBody JsonNode payload, HttpServletRequest request) {
        JsonNode ops = payload == null ? null : payload.get("operations");
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            throw new IllegalArgumentException("operations must be a non-empty array");
        }
        if (ops.size() > MAX_BATCH) throw new IllegalArgumentException("a batch may contain at most " + MAX_BATCH + " operations");
        boolean atomic = payload.path("atomic").asBoolean(false);
        boolean stopOnError = atomic || payload.path("stopOnError").asBoolean(false);

        // Validate everything up front so a malformed op can't half-run a batch.
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            String id = op.path("id").asText("op" + i);
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate operation id: " + id);
            String method = op.path("method").asText("GET").toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                throw new IllegalArgumentException("operation " + id + ": unsupported method " + method);
            }
            requireWriteScopeIfNeeded(request, method);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, JsonNode> bodies = new HashMap<>();
        boolean[] failed = {false};

        Runnable run = () -> {
            for (int i = 0; i < ops.size(); i++) {
                JsonNode op = ops.get(i);
                String id = op.path("id").asText("op" + i);
                String method = op.path("method").asText("GET").toUpperCase(Locale.ROOT);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                try {
                    String path = resolveRefs(op.path("path").asText(""), bodies, false);
                    String target = targetPath(path, request) + query(op.get("query"), bodies);
                    byte[] body = null;
                    if (op.has("body") && !op.get("body").isNull()) {
                        body = resolveRefs(objectMapper.writeValueAsString(op.get("body")), bodies, true)
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    InternalDispatcher.Result res = dispatcher.execute(request, method, target, body);
                    r.put("status", res.status());
                    JsonNode parsed = parse(res);
                    r.put("body", parsed);
                    bodies.put(id, parsed);
                    if (res.status() >= 400) failed[0] = true;
                } catch (IllegalArgumentException | AccessDeniedException e) {
                    r.put("status", e instanceof AccessDeniedException ? 403 : 400);
                    r.put("body", Map.of("error", String.valueOf(e.getMessage())));
                    failed[0] = true;
                } catch (Exception e) {
                    r.put("status", 500);
                    r.put("body", Map.of("error", "operation failed"));
                    failed[0] = true;
                }
                results.add(r);
                if (failed[0] && stopOnError) break;
            }
        };

        boolean rolledBack = false;
        if (atomic) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    run.run();
                    if (failed[0]) status.setRollbackOnly();
                });
            } catch (Exception commitFailure) {
                failed[0] = true;
            }
            rolledBack = failed[0];
        } else {
            run.run();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", !failed[0]);
        out.put("atomic", atomic);
        out.put("rolledBack", rolledBack);
        out.put("executed", results.size());
        out.put("results", results);
        return ResponseEntity.status(failed[0] && atomic ? 409 : 200).body(out);
    }

    // ── pass-through ────────────────────────────────────────────────────────

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String rest = uri.substring(BASE.length());
        requireWriteScopeIfNeeded(request, request.getMethod());
        String target = targetPath(rest, request);
        response.setHeader("X-Master-Api-Version", VERSION);
        // The original query parameters and body travel with the forwarded request.
        request.getRequestDispatcher(target).forward(request, response);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String targetPath(String rest, HttpServletRequest request) {
        if (rest == null || rest.isEmpty() || rest.equals("/")) throw new IllegalArgumentException("a resource path is required");
        if (!rest.startsWith("/")) rest = "/" + rest;
        String pathOnly = rest.contains("?") ? rest.substring(0, rest.indexOf('?')) : rest;
        if (!SAFE_PATH.matcher(pathOnly).matches()) throw new IllegalArgumentException("invalid resource path");
        for (String seg : pathOnly.split("/")) {
            if (seg.equals(".") || seg.equals("..")) throw new IllegalArgumentException("invalid resource path");
        }
        if (isBlocked(pathOnly)) throw new AccessDeniedException("this endpoint is not available through the Master API");
        if (request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY) != null && blockedForKeys(pathOnly)) {
            throw new AccessDeniedException("this endpoint is not available to API keys");
        }
        return "/api" + rest;
    }

    private static boolean isBlocked(String rest) {
        for (String b : BLOCKED_PREFIXES) {
            if (rest.equals(b) || rest.startsWith(b + "/")) return true;
        }
        return false;
    }

    private static boolean blockedForKeys(String rest) {
        for (Pattern p : BLOCKED_FOR_KEYS) if (p.matcher(rest).matches()) return true;
        return false;
    }

    private static void requireWriteScopeIfNeeded(HttpServletRequest request, String method) {
        ApiKey key = (ApiKey) request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY);
        boolean read = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
        if (key != null && !read && !key.writeAllowed()) {
            throw new AccessDeniedException("this API key is read-only");
        }
    }

    private String query(JsonNode q, Map<String, JsonNode> bodies) {
        if (q == null || !q.isObject() || q.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("&", "?", "");
        q.fields().forEachRemaining(f -> {
            String v = resolveRefs(f.getValue().isTextual() ? f.getValue().asText() : f.getValue().toString(), bodies, false);
            sj.add(URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sj.toString();
    }

    /** Replaces {{opId.body.a.b}} with values from earlier results (JSON-aware inside bodies). */
    private String resolveRefs(String text, Map<String, JsonNode> bodies, boolean json) {
        if (text == null || !text.contains("{{")) return text;
        String out = text;
        if (json) {
            Matcher m = QUOTED_REF.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                JsonNode v = lookup(bodies, m.group(1), m.group(2));
                m.appendReplacement(sb, Matcher.quoteReplacement(v.toString()));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        Matcher m = REF.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = lookup(bodies, m.group(1), m.group(2));
            String s = v.isValueNode() ? v.asText() : v.toString();
            if (json) s = escapeJsonFragment(s);
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonNode lookup(Map<String, JsonNode> bodies, String opId, String dotted) {
        JsonNode node = bodies.get(opId);
        if (node == null) throw new IllegalArgumentException("reference to unknown or not-yet-run operation: " + opId);
        for (String part : dotted.split("\\.")) {
            if (part.isEmpty()) continue;
            node = node.path(part);
        }
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("reference {{" + opId + ".body" + dotted + "}} did not resolve");
        }
        return node;
    }

    private static String escapeJsonFragment(String s) {
        String q;
        try {
            q = new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            return "";
        }
        return q.substring(1, q.length() - 1);
    }

    private JsonNode parse(InternalDispatcher.Result res) {
        if (res.body().length == 0) return objectMapper.nullNode();
        String ct = res.contentType() == null ? "" : res.contentType();
        if (ct.contains("json")) {
            try {
                return objectMapper.readTree(res.body());
            } catch (Exception ignored) {
                // fall through to text
            }
        }
        ObjectNode n = objectMapper.createObjectNode();
        n.put("contentType", ct);
        n.put("size", res.body().length);
        n.put("note", "non-JSON response omitted from batch; call the endpoint directly to download it");
        return n;
    }
}
