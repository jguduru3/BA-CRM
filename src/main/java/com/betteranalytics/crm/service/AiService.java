package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.betteranalytics.crm.config.IntegrationAsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes AI automation jobs (score_lead, draft_message, summarize, next_action)
 * synchronously against OpenAI or Anthropic, using the org's stored API key.
 * Each invocation persists an ai_jobs row and fires an FCM push on completion.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    public static final String DEFAULT_OPENAI_MODEL    = "gpt-4o-mini";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";
    private static final String ANTHROPIC_VERSION      = "2023-06-01";

    private final OrgSettingsRepository settingsRepo;
    private final AiJobRepository       jobRepo;
    private final LeadRepository        leadRepo;
    private final ActivityRepository    activityRepo;
    private final NotificationService   notificationService;
    private final ObjectMapper          objectMapper;
    private final RestTemplate          restTemplate;

    public AiService(OrgSettingsRepository settingsRepo,
                     AiJobRepository jobRepo,
                     LeadRepository leadRepo,
                     ActivityRepository activityRepo,
                     NotificationService notificationService,
                     ObjectMapper objectMapper,
                     RestTemplateBuilder restTemplateBuilder) {
        this.settingsRepo      = settingsRepo;
        this.jobRepo           = jobRepo;
        this.leadRepo          = leadRepo;
        this.activityRepo      = activityRepo;
        this.notificationService = notificationService;
        this.objectMapper      = objectMapper;
        // 5s connect / 60s read, no redirects (RestTemplateBuilder kept in the
        // signature for wiring compatibility).
        this.restTemplate      = IntegrationAsyncConfig.outboundRestTemplate(5_000, 60_000);
    }

    // ── Public entry points ───────────────────────────────────────────────────

    public AiJob scoreLead(Long orgId, Long leadId, Long userId) {
        Lead lead = requireLead(orgId, leadId);
        List<Activity> acts = recentActivities(orgId, leadId);
        String system =
                "You are a CRM lead scoring assistant. Score the lead 0-100 based on " +
                "engagement level, information completeness, and buying-fit indicators. " +
                "Respond ONLY with valid JSON: " +
                "{\"score\": number, \"reasoning\": string, \"top_factors\": [string]}";
        return run(orgId, "score_lead", "Lead", leadId, userId,
                   requireSettings(orgId), system, buildLeadContext(lead, acts));
    }

    public AiJob draftMessage(Long orgId, Long leadId, String channel,
                               String context, Long userId) {
        Lead lead = requireLead(orgId, leadId);
        List<Activity> acts = recentActivities(orgId, leadId);
        String ch = (channel != null && !channel.isBlank()) ? channel : "whatsapp";
        String system =
                "You are a sales communication assistant. Draft a concise, personalized " +
                ch + " message for the sales rep to send to this lead. " +
                "Respond ONLY with valid JSON: " +
                "{\"message\": string, \"subject\": string, \"tone\": string}";
        String userPrompt = buildLeadContext(lead, acts) +
                (context != null ? "\n\nPurpose: " + context : "");
        return run(orgId, "draft_message", "Lead", leadId, userId,
                   requireSettings(orgId), system, userPrompt);
    }

    public AiJob summarize(Long orgId, Long leadId, Long userId) {
        Lead lead = requireLead(orgId, leadId);
        List<Activity> acts = recentActivities(orgId, leadId);
        String system =
                "You are a CRM assistant. Summarize this lead's interaction history " +
                "in 2-3 sentences, highlighting key developments. " +
                "Respond ONLY with valid JSON: " +
                "{\"summary\": string, \"sentiment\": string, \"key_points\": [string]}";
        return run(orgId, "summarize", "Lead", leadId, userId,
                   requireSettings(orgId), system, buildLeadContext(lead, acts));
    }

    public AiJob nextAction(Long orgId, Long leadId, Long userId) {
        Lead lead = requireLead(orgId, leadId);
        List<Activity> acts = recentActivities(orgId, leadId);
        String system =
                "You are a sales coach. Recommend the single most impactful next action " +
                "for this lead right now. " +
                "Respond ONLY with valid JSON: " +
                "{\"action\": string, \"reason\": string, \"urgency\": string, \"channel\": string}";
        return run(orgId, "next_action", "Lead", leadId, userId,
                   requireSettings(orgId), system, buildLeadContext(lead, acts));
    }

    // ── Core execution ────────────────────────────────────────────────────────

    private AiJob run(Long orgId, String type, String entityType, Long entityId,
                      Long userId, OrgSettings settings,
                      String systemPrompt, String userPrompt) {
        AiJob job = new AiJob();
        job.setOrganizationId(orgId);
        job.setType(type);
        job.setEntityType(entityType);
        job.setEntityId(entityId);
        job.setStatus("running");
        job.setCreatedBy(userId);
        job.setCreatedAt(Instant.now());
        try {
            job.setInputJson(objectMapper.writeValueAsString(
                    Map.of("system", systemPrompt, "user", userPrompt)));
        } catch (Exception ignored) {}
        job = jobRepo.save(job);

        try {
            AiResult result = callAi(settings, systemPrompt, userPrompt);
            job.setStatus("done");
            job.setResultJson(result.content());
            job.setModelUsed(result.model());
            job.setTokensUsed(result.tokens());
            job = jobRepo.save(job);
            notificationService.notify(orgId, userId,
                    "ai_job_done",
                    "AI " + type.replace("_", " ") + " complete",
                    entityType + " " + entityId + " processed by " + result.model(),
                    entityType, entityId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            job.setStatus("failed");
            job.setError(e.getMessage());
        } catch (Exception e) {
            log.warn("AI job {} failed: {}", job.getId(), e.toString());
            job.setStatus("failed");
            job.setError("AI request failed");
        }
        job.setCompletedAt(Instant.now());
        return jobRepo.save(job);
    }

    // ── Provider dispatch ─────────────────────────────────────────────────────

    private AiResult callAi(OrgSettings settings, String systemPrompt, String userPrompt) {
        if ("anthropic".equalsIgnoreCase(settings.getAiProvider())) {
            return callAnthropic(settings, systemPrompt, userPrompt);
        }
        return callOpenAi(settings, systemPrompt, userPrompt);
    }

    private AiResult callOpenAi(OrgSettings settings, String systemPrompt, String userPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(settings.getAiApiKey());

        String model = modelFor("openai", settings.getAiModel());
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "max_tokens", 1000,
                "response_format", Map.of("type", "json_object")
        );
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    "https://api.openai.com/v1/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            int tokens     = root.path("usage").path("total_tokens").asInt(0);
            return new AiResult(content, model, tokens);
        } catch (Exception e) {
            throw providerError("OpenAI", e);
        }
    }

    private AiResult callAnthropic(OrgSettings settings, String systemPrompt, String userPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", settings.getAiApiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        String model = modelFor("anthropic", settings.getAiModel());
        Map<String, Object> body = Map.of(
                "model",    model,
                "max_tokens", 1000,
                "system",   systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    "https://api.anthropic.com/v1/messages",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode root   = objectMapper.readTree(resp.getBody());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
            }
            String content  = text.toString();
            int tokens      = root.path("usage").path("input_tokens").asInt(0)
                            + root.path("usage").path("output_tokens").asInt(0);
            return new AiResult(content, model, tokens);
        } catch (Exception e) {
            throw providerError("Anthropic", e);
        }
    }

    /** Maps provider failures to user-safe messages; raw response bodies only go to the log. */
    private IllegalStateException providerError(String provider, Exception e) {
        if (e instanceof HttpStatusCodeException h) {
            int code = h.getStatusCode().value();
            String body = h.getResponseBodyAsString();
            log.warn("{} API returned HTTP {}: {}", provider, code, body.length() > 500 ? body.substring(0, 500) : body);
            if (code == 401 || code == 403)
                return new IllegalStateException(provider + " rejected the API key (HTTP " + code + ")");
            if (code == 429) return new IllegalStateException(provider + " rate limit reached (HTTP 429)");
            return new IllegalStateException(provider + " API call failed (HTTP " + code + ")");
        }
        if (e instanceof ResourceAccessException)
            return new IllegalStateException(provider + " API unreachable or timed out");
        log.warn("{} API call failed: {}", provider, e.toString());
        return new IllegalStateException(provider + " API call failed");
    }

    /** Uses the configured model unless it belongs to the other provider (e.g. after switching). */
    public static String modelFor(String provider, String configured) {
        boolean anthropic = "anthropic".equalsIgnoreCase(provider);
        String def = anthropic ? DEFAULT_ANTHROPIC_MODEL : DEFAULT_OPENAI_MODEL;
        if (configured == null || configured.isBlank()) return def;
        boolean claude = configured.toLowerCase(Locale.ROOT).startsWith("claude");
        return anthropic == claude ? configured : def;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgSettings requireSettings(Long orgId) {
        OrgSettings s = settingsRepo.findByOrganizationId(orgId).orElse(null);
        if (s == null || !s.isAiEnabled())
            throw new IllegalStateException("AI is not enabled for this organization");
        if (s.getAiApiKey() == null || s.getAiApiKey().isBlank())
            throw new IllegalStateException("AI API key is not configured");
        return s;
    }

    private Lead requireLead(Long orgId, Long leadId) {
        return leadRepo.findByIdAndOrganizationId(leadId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Lead not found: " + leadId));
    }

    private List<Activity> recentActivities(Long orgId, Long leadId) {
        return activityRepo
                .findByOrganizationIdAndRelatedTypeAndRelatedId(orgId, "Lead", leadId)
                .stream()
                .sorted(Comparator.comparing(Activity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .collect(Collectors.toList());
    }

    private String buildLeadContext(Lead lead, List<Activity> activities) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lead: ").append(lead.getName());
        if (lead.getEmail()         != null) sb.append("\nEmail: ").append(lead.getEmail());
        if (lead.getPhone()         != null) sb.append("\nPhone: ").append(lead.getPhone());
        sb.append("\nStage: ").append(lead.getStage());
        sb.append("\nPriority: ").append(lead.getPriority());
        if (lead.getIndustry()      != null) sb.append("\nIndustry: ").append(lead.getIndustry());
        if (lead.getGeography()     != null) sb.append("\nGeography: ").append(lead.getGeography());
        if (lead.getRequirement()   != null) sb.append("\nRequirement: ").append(lead.getRequirement());
        if (lead.getPainPoint()     != null) sb.append("\nPain point: ").append(lead.getPainPoint());
        if (lead.getNotes()         != null) sb.append("\nNotes: ").append(lead.getNotes());

        if (!activities.isEmpty()) {
            sb.append("\n\nRecent activities (newest first):");
            for (Activity a : activities) {
                sb.append("\n- [").append(a.getType()).append("]");
                if (a.getOutcome() != null) sb.append(" outcome=").append(a.getOutcome());
                if (a.getBody()    != null) {
                    String snippet = a.getBody().length() > 200
                            ? a.getBody().substring(0, 200) + "…"
                            : a.getBody();
                    sb.append(": ").append(snippet);
                }
            }
        }
        return sb.toString();
    }

    private record AiResult(String content, String model, int tokens) {}
}
