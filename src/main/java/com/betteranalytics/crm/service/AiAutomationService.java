package com.betteranalytics.crm.service;

import com.betteranalytics.crm.config.IntegrationAsyncConfig;
import com.betteranalytics.crm.config.IntegrationAsyncConfig.IntegrationExecutor;
import com.betteranalytics.crm.entity.AiAutomation;
import com.betteranalytics.crm.entity.AiAutomationRun;
import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.entity.Note;
import com.betteranalytics.crm.entity.OrgSettings;
import com.betteranalytics.crm.repository.AiAutomationRepository;
import com.betteranalytics.crm.repository.AiAutomationRunRepository;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.EmailAccountRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.NoteRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.repository.OrgSettingsRepository;
import com.betteranalytics.crm.security.UrlSafety;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.*;

/**
 * Executes AI automations — resolves prompt templates, calls the configured AI
 * provider (Anthropic Messages API or OpenAI Chat Completions), sends/reads
 * email, calls custom APIs, stores run results.
 *
 * Template syntax: {{variable_name}}. Substitution is context-aware:
 *   prompts / subject / body : plain text
 *   custom_api url           : values are URL-encoded; scheme + host must be literal
 *                              in the template and may not change after substitution
 *   custom_api headers/body  : values are JSON-string-escaped
 *   send_email to            : must resolve to exactly one valid address
 *
 * Action types and their action_config_json shape:
 *   generate_content / prepare_strategy / analyze_data:
 *     { "systemPrompt": "...", "promptTemplate": "...", "maxTokens": 2000 }
 *   send_email:
 *     { "emailAccountId": 1, "to": "{{contact.email}}", "subject": "...",
 *       "promptTemplate": "Write an email about ...", "maxTokens": 1000 }
 *   read_email:
 *     { "emailAccountId": 1, "folder": "INBOX", "limit": 10,
 *       "analyzePrompt": "Summarize key action items from these emails" }
 *   custom_api:
 *     { "url": "https://api.example.com/items/{{id}}", "method": "POST",
 *       "headersJson": "{\"Authorization\":\"Bearer {{token}}\"}",
 *       "bodyTemplate": "{\"input\":\"{{input}}\"}" }
 *
 * Saving to the record (generate_content / prepare_strategy / analyze_data):
 *   "saveToRecord": true|false — when the run's context names a record (lead.id,
 *   contact.id, opportunity.id or company.id — every record event does), the output
 *   is appended as a Note "AI: <automation name>" on that record, authored by the
 *   automation's creator, after the triggering transaction commits. Default: true
 *   whenever such a record is in the context.
 *
 * custom_api / send_email / read_email are privileged (SUPER_ADMIN / VP_SALES
 * only to create or modify — enforced in AiAutomationController).
 */
@Service
public class AiAutomationService {

    private static final Logger log = LoggerFactory.getLogger(AiAutomationService.class);
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o";
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern SINGLE_VAR = Pattern.compile("^\\s*\\{\\{([^}]+)}}\\s*$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}$");
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer",
            "proxy-authorization", "proxy-connection", "expect");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_RESPONSE_BYTES = 10 * 1024;
    private static final int MAX_TOKENS_CAP = 8000;

    public static final Set<String> TRIGGER_TYPES = Set.of(
            "manual", "scheduled", "on_lead_created", "on_stage_change", "on_campaign_approved", "webhook");
    public static final Set<String> ACTION_TYPES = Set.of(
            "generate_content", "prepare_strategy", "analyze_data", "send_email", "read_email", "custom_api");
    public static final Set<String> PRIVILEGED_ACTION_TYPES = Set.of("custom_api", "send_email", "read_email");
    public static final Set<String> PROVIDERS = Set.of("anthropic", "openai");
    /** Action types whose output is AI-generated text that can be saved as a Note. */
    public static final Set<String> NOTE_ACTION_TYPES = Set.of("generate_content", "prepare_strategy", "analyze_data");
    /** Context keys that identify the record a run is about, in priority order. */
    private static final List<String[]> RECORD_KEYS = List.of(
            new String[]{"lead.id", "Lead"}, new String[]{"contact.id", "Contact"},
            new String[]{"opportunity.id", "Opportunity"}, new String[]{"company.id", "Company"});

    private final AiAutomationRepository automationRepo;
    private final AiAutomationRunRepository runRepo;
    private final EmailAccountRepository emailAccountRepo;
    private final OrgSettingsRepository orgSettingsRepo;
    private final EmailIntegrationService emailService;
    private final IntegrationExecutor executor;
    private final ObjectMapper objectMapper;
    private final RestTemplate aiRestTemplate;
    private final RestTemplate customApiRestTemplate;
    private final NoteRepository noteRepo;
    private final LeadRepository leadRepo;
    private final ContactRepository contactRepo;
    private final OpportunityRepository opportunityRepo;
    private final CompanyRepository companyRepo;
    /** Own transaction for the note (an afterCommit callback must not join the finished one). */
    private final org.springframework.transaction.support.TransactionTemplate txNew;

    public AiAutomationService(AiAutomationRepository automationRepo,
                                AiAutomationRunRepository runRepo,
                                EmailAccountRepository emailAccountRepo,
                                OrgSettingsRepository orgSettingsRepo,
                                EmailIntegrationService emailService,
                                IntegrationExecutor executor,
                                NoteRepository noteRepo,
                                LeadRepository leadRepo,
                                ContactRepository contactRepo,
                                OpportunityRepository opportunityRepo,
                                CompanyRepository companyRepo,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.txNew = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.noteRepo = noteRepo;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.companyRepo = companyRepo;
        this.automationRepo = automationRepo;
        this.runRepo = runRepo;
        this.emailAccountRepo = emailAccountRepo;
        this.orgSettingsRepo = orgSettingsRepo;
        this.emailService = emailService;
        this.executor = executor;
        this.objectMapper = new ObjectMapper();
        this.aiRestTemplate = IntegrationAsyncConfig.outboundRestTemplate(5_000, 60_000);
        this.customApiRestTemplate = IntegrationAsyncConfig.outboundRestTemplate(5_000, 30_000);
        // Custom API: report any HTTP status back to the run instead of throwing with the raw body.
        this.customApiRestTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
            @Override public void handleError(ClientHttpResponse response) { }
        });
    }

    // ── Definition validation (used by AiAutomationController) ─────────────────

    /**
     * Validates an automation definition; IllegalArgumentException (→ 400) on problems.
     * For custom_api the URL's scheme + host must be literal and public.
     */
    public void validateDefinition(String triggerType, String actionType, String actionConfigJson,
                                   String apiProvider, String modelId) {
        if (triggerType == null || !TRIGGER_TYPES.contains(triggerType))
            throw new IllegalArgumentException("triggerType must be one of " + TRIGGER_TYPES);
        if (actionType == null || !ACTION_TYPES.contains(actionType))
            throw new IllegalArgumentException("actionType must be one of " + ACTION_TYPES);
        if (apiProvider != null && !PROVIDERS.contains(apiProvider))
            throw new IllegalArgumentException("apiProvider must be 'anthropic' or 'openai'");
        if (modelId != null && !MODEL_ID.matcher(modelId).matches())
            throw new IllegalArgumentException("modelId is not valid");

        Map<String, Object> config;
        if (actionConfigJson == null || actionConfigJson.isBlank()) {
            config = Map.of();
        } else {
            try {
                config = objectMapper.readValue(actionConfigJson, new TypeReference<>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("actionConfigJson must be a JSON object");
            }
            if (config == null) config = Map.of();
        }
        switch (actionType) {
            case "custom_api" -> {
                String url = str(config, "url", null);
                if (url == null || url.isBlank()) throw new IllegalArgumentException("custom_api requires a url");
                literalOrigin(url);
                String method = str(config, "method", "POST").toUpperCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) throw new IllegalArgumentException("method must be one of " + HTTP_METHODS);
            }
            case "send_email", "read_email" -> {
                if (longVal(config, "emailAccountId") == null)
                    throw new IllegalArgumentException(actionType + " requires an integer emailAccountId");
            }
            default -> { }
        }
    }

    /** Trigger config must be valid for its trigger type ("scheduled" needs a parseable cron). */
    public void validateTrigger(String triggerType, String triggerConfigJson) {
        if ("scheduled".equals(triggerType)) schedule(triggerConfigJson);
        else if (triggerConfigJson != null && !triggerConfigJson.isBlank()) {
            try { objectMapper.readTree(triggerConfigJson); }
            catch (Exception e) { throw new IllegalArgumentException("triggerConfigJson must be valid JSON"); }
        }
    }

    // ── Scheduled (cron) trigger ──────────────────────────────────────────────

    public record Schedule(CronExpression cron, ZoneId zone) {}

    /**
     * Parses {"cron": "0 9 * * MON-FRI", "timezone": "Asia/Kolkata"} from trigger_config_json.
     * Accepts standard 5-field cron (minute precision) or Spring's 6-field form
     * (seconds first). Timezone defaults to UTC. Problems → IllegalArgumentException (400).
     */
    public Schedule schedule(String triggerConfigJson) {
        Map<String, Object> cfg = parseJson(triggerConfigJson);
        String expr = str(cfg, "cron", null);
        if (expr == null || expr.isBlank())
            throw new IllegalArgumentException("A scheduled automation needs triggerConfigJson {\"cron\": \"0 9 * * MON-FRI\"}");
        String e = expr.trim();
        if (e.split("\\s+").length == 5) e = "0 " + e;
        CronExpression cron;
        try {
            cron = CronExpression.parse(e);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cron is not a valid cron expression: " + ex.getMessage());
        }
        String tz = str(cfg, "timezone", null);
        ZoneId zone;
        try {
            zone = tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("timezone is not a valid time zone id");
        }
        return new Schedule(cron, zone);
    }

    /** Next fire time strictly after {@code after}, or null when the cron never fires again. */
    public static Instant nextFire(Schedule s, Instant after) {
        ZonedDateTime next = s.cron().next(after.atZone(s.zone()));
        return next == null ? null : next.toInstant();
    }

    /**
     * Every minute, cluster-wide once (ShedLock): fires active "scheduled"
     * automations whose next cron time (after the last fire, or after the last
     * edit) has passed. Missed slots (downtime) fire once, not once per slot.
     * Runs are queued on the bounded executor — this job never calls AI/HTTP itself.
     */
    @Scheduled(fixedDelayString = "${app.automations.scheduler-delay-ms:60000}",
               initialDelayString = "${app.automations.scheduler-initial-delay-ms:45000}")
    @SchedulerLock(name = "automations.cron", lockAtMostFor = "PT5M")
    public void runDueScheduled() {
        try {
            fireDueScheduled(Instant.now());
        } catch (Exception e) {
            log.error("Automation cron scan failed: {}", e.toString(), e);
        }
    }

    /** Returns how many automations were queued (exposed for tests). */
    public int fireDueScheduled(Instant now) {
        int queued = 0;
        for (AiAutomation a : automationRepo.findByTriggerTypeAndIsActiveTrue("scheduled")) {
            try {
                Schedule s = schedule(a.getTriggerConfigJson());
                Instant base = a.getScheduleLastFiredAt() != null ? a.getScheduleLastFiredAt()
                        : (a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt());
                if (base == null) base = now.minus(Duration.ofMinutes(1));
                Instant next = nextFire(s, base);
                if (next == null || next.isAfter(now)) continue;
                int claimed = a.getScheduleLastFiredAt() == null
                        ? automationRepo.claimFirstScheduledFire(a.getId(), now)
                        : automationRepo.claimScheduledFire(a.getId(), a.getScheduleLastFiredAt(), now);
                if (claimed != 1) continue;
                Map<String, String> ctx = new HashMap<>();
                ctx.put("schedule.firedAt", now.toString());
                ctx.put("schedule.slot", next.toString());
                runAsync(a.getId(), a.getOrganizationId(), ctx, "scheduled");
                queued++;
            } catch (IllegalArgumentException bad) {
                log.warn("Scheduled automation {} has an invalid schedule: {}", a.getId(), bad.getMessage());
            }
        }
        return queued;
    }

    // ── Webhook trigger ───────────────────────────────────────────────────────

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final Duration WEBHOOK_TOLERANCE = Duration.ofMinutes(5);

    /** New random per-automation HMAC secret (shown to the user once). */
    public static String generateWebhookSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }

    /**
     * X-Signature: sha256=hex(HMAC-SHA256(secret, timestamp + "." + rawBody)),
     * X-Timestamp: unix seconds within ±5 minutes of now. Constant-time compare.
     */
    public static boolean verifyWebhookSignature(String secret, String timestampHeader, byte[] rawBody,
                                                 String signatureHeader, Instant now) {
        if (secret == null || secret.isBlank() || timestampHeader == null || signatureHeader == null) return false;
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - ts) > WEBHOOK_TOLERANCE.toSeconds()) return false;
        String sig = signatureHeader.trim();
        if (!sig.startsWith("sha256=")) return false;
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(sig.substring(7));
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected = webhookSignature(secret, timestampHeader.trim(), rawBody);
        return MessageDigest.isEqual(expected, provided);
    }

    public static byte[] webhookSignature(String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(rawBody != null ? rawBody : new byte[0]);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Queue one run on the bounded executor (after commit when a transaction is active). */
    public void runAsync(Long automationId, Long orgId, Map<String, String> context, String source) {
        Map<String, String> ctx = context != null ? new HashMap<>(context) : new HashMap<>();
        Runnable submit = () -> {
            try {
                executor.submit("automation-" + source + "-" + automationId,
                        () -> execute(automationId, orgId, null, ctx, source));
            } catch (TaskRejectedException e) {
                log.warn("Dropped {} run of automation {}: executor queue full", source, automationId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    /** Email account referenced by a definition must belong to the org. */
    public void requireEmailAccountInOrg(String actionConfigJson, Long orgId) {
        Long accountId = longVal(parseJson(actionConfigJson), "emailAccountId");
        if (accountId != null && emailAccountRepo.findByIdAndOrganizationId(accountId, orgId).isEmpty())
            throw new IllegalArgumentException("emailAccountId does not refer to an email account in your organization");
    }

    // ── Event firing ──────────────────────────────────────────────────────────

    /**
     * Event-driven firing — called from domain code (lead created, stage
     * changed, campaign approved...). Never blocks or fails the caller: the
     * work is queued on the bounded integration executor, after the caller's
     * transaction commits when one is active. Each automation is isolated and
     * every run is logged (status, error, duration_ms).
     */
    public void fireEvent(Long orgId, String triggerType, Map<String, String> context) {
        Map<String, String> ctx = context != null ? new HashMap<>(context) : new HashMap<>();
        Runnable submit = () -> {
            try {
                executor.submit("automation-event-" + triggerType, () -> runEvent(orgId, triggerType, ctx));
            } catch (TaskRejectedException e) {
                log.warn("Dropped '{}' automation event for org {}: executor queue full", triggerType, orgId);
            } catch (Exception e) {
                log.warn("Could not queue '{}' automation event for org {}: {}", triggerType, orgId, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    private void runEvent(Long orgId, String triggerType, Map<String, String> ctx) {
        List<AiAutomation> matches = automationRepo.findByOrganizationIdAndTriggerTypeAndIsActiveTrue(orgId, triggerType);
        for (AiAutomation automation : matches) {
            try {
                execute(automation.getId(), orgId, null, ctx, "event");
            } catch (Exception e) {
                log.warn("Event-fired automation {} ({}) failed for org {}: {}",
                        automation.getId(), triggerType, orgId, e.toString());
            }
        }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Manually trigger an automation. Context map provides {{variable}} substitutions.
     * Returns the run record (with output_text / action_result_json populated).
     * Unknown id → NoSuchElementException (404).
     */
    public AiAutomationRun executeManual(Long automationId, Long orgId,
                                          Long triggeredBy, Map<String, String> context) {
        return execute(automationId, orgId, triggeredBy, context, "manual");
    }

    /**
     * Runs one automation and records the run (status, output, error, duration_ms,
     * trigger source). Never called inside a DB transaction: the run row is saved,
     * the provider / HTTP / SMTP call happens with timeouts, then the row is updated.
     */
    public AiAutomationRun execute(Long automationId, Long orgId, Long triggeredBy,
                                   Map<String, String> context, String source) {
        AiAutomation automation = automationRepo.findByIdAndOrganizationId(automationId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Automation not found"));
        Map<String, String> ctx = context != null ? context : Map.of();

        AiAutomationRun run = new AiAutomationRun();
        run.setAutomationId(automationId);
        run.setOrganizationId(orgId);
        run.setStatus("running");
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(triggeredBy);
        run.setTriggerSource(source);
        long t0 = System.nanoTime();
        try { run.setTriggerPayloadJson(objectMapper.writeValueAsString(ctx)); } catch (Exception ignored) {}
        run = runRepo.save(run);

        try {
            Map<String, Object> result = execute(automation, ctx);
            run.setOutputText(result.get("output") instanceof String s ? s : null);
            run.setStatus("completed");
            result = saveOutputToRecord(automation, ctx, run.getOutputText(), result);
            try { run.setActionResultJson(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            run.setTokensUsed(result.get("tokens") instanceof Number n ? n.intValue() : null);
        } catch (Exception e) {
            log.warn("Automation {} failed: {}", automationId, e.toString());
            run.setStatus("failed");
            run.setErrorMessage(safeError(e));
        }
        run.setCompletedAt(Instant.now());
        run.setDurationMs((System.nanoTime() - t0) / 1_000_000);
        run = runRepo.save(run);

        automationRepo.recordRun(automationId, Instant.now());
        return run;
    }

    /** The record a run's context is about: [relatedType, id], or null. */
    static Object[] recordRef(Map<String, String> ctx) {
        for (String[] k : RECORD_KEYS) {
            String v = ctx.get(k[0]);
            if (v == null || v.isBlank()) continue;
            try {
                return new Object[]{k[1], Long.parseLong(v.trim())};
            } catch (NumberFormatException ignored) {
                // not an id: try the next key
            }
        }
        return null;
    }

    /** saveToRecord: explicit boolean wins; absent → true (only effective when the context names a record). */
    static boolean saveToRecord(Map<String, Object> config) {
        Object v = config.get("saveToRecord");
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return !"false".equalsIgnoreCase(s.trim());
        return true;
    }

    /**
     * Appends the generated output as a Note on the context's record (see class doc).
     * Runs after the caller's transaction commits when one is active; otherwise now,
     * and the note id is added to the run result. A failure here never fails the run.
     */
    private Map<String, Object> saveOutputToRecord(AiAutomation automation, Map<String, String> ctx, String output,
                                                   Map<String, Object> result) {
        if (output == null || output.isBlank() || !NOTE_ACTION_TYPES.contains(automation.getActionType())) return result;
        Object[] ref = recordRef(ctx);
        if (ref == null || !saveToRecord(parseJson(automation.getActionConfigJson()))) return result;
        String type = (String) ref[0];
        Long id = (Long) ref[1];
        Long org = automation.getOrganizationId();
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("savedTo", type + ":" + id);
        Runnable save = () -> {
            try {
                if (!recordExists(type, id, org)) {
                    log.warn("Automation {}: {} {} not found in org {}; output not saved as a note", automation.getId(), type, id, org);
                    out.remove("savedTo");
                    return;
                }
                Note n = new Note();
                n.setOrganizationId(org);
                String title = "AI: " + automation.getName();
                n.setTitle(title.length() > 300 ? title.substring(0, 300) : title);
                n.setBody(output);
                n.setRelatedType(type);
                n.setRelatedId(id);
                n.setCreatedBy(automation.getCreatedBy());
                Instant now = Instant.now();
                n.setCreatedAt(now);
                n.setUpdatedAt(now);
                Note saved = txNew.execute(st -> noteRepo.save(n));
                if (saved != null) out.put("noteId", saved.getId());
            } catch (Exception e) {
                log.warn("Automation {} output could not be saved as a note: {}", automation.getId(), e.toString());
                out.remove("savedTo");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { save.run(); }
            });
        } else {
            save.run();
        }
        return out;
    }

    private boolean recordExists(String type, Long id, Long org) {
        return switch (type) {
            case "Lead" -> leadRepo.findByIdAndOrganizationId(id, org).isPresent();
            case "Contact" -> contactRepo.findByIdAndOrganizationId(id, org).isPresent();
            case "Opportunity" -> opportunityRepo.findByIdAndOrganizationId(id, org).isPresent();
            case "Company" -> companyRepo.findByIdAndOrganizationId(id, org).isPresent();
            default -> false;
        };
    }

    /** Only messages we authored reach the client; anything else becomes a generic message. */
    private static String safeError(Exception e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException
                || e instanceof NoSuchElementException) {
            String m = e.getMessage();
            return m == null ? "Automation failed" : (m.length() > 1000 ? m.substring(0, 1000) : m);
        }
        return "Automation failed (" + e.getClass().getSimpleName() + ")";
    }

    /**
     * Internal: execute the automation's action and return result map.
     */
    private Map<String, Object> execute(AiAutomation automation, Map<String, String> ctx) {
        Map<String, Object> config = parseJson(automation.getActionConfigJson());
        AiCreds creds = resolveCreds(automation);

        return switch (automation.getActionType()) {
            case "generate_content", "prepare_strategy", "analyze_data" ->
                    runContentGeneration(creds, config, ctx);
            case "send_email" -> runSendEmail(creds, config, ctx, automation.getOrganizationId());
            case "read_email" -> runReadEmail(config, creds, automation.getOrganizationId());
            case "custom_api" -> runCustomApi(config, ctx);
            default -> runContentGeneration(creds, config, ctx);
        };
    }

    private record AiCreds(String provider, String apiKey, String model) {
        boolean hasKey() { return apiKey != null && !apiKey.isBlank(); }
    }

    /** Automation's own key, else the org's AI settings key when the provider matches. */
    private AiCreds resolveCreds(AiAutomation automation) {
        String provider = automation.getApiProvider() != null
                ? automation.getApiProvider().toLowerCase(Locale.ROOT) : "anthropic";
        if (!PROVIDERS.contains(provider)) provider = "anthropic";
        String key = automation.getApiKeyEncrypted();
        if (key == null || key.isBlank()) {
            OrgSettings s = orgSettingsRepo.findByOrganizationId(automation.getOrganizationId()).orElse(null);
            if (s != null && s.isAiEnabled() && provider.equalsIgnoreCase(s.getAiProvider())) key = s.getAiApiKey();
        }
        return new AiCreds(provider, key, effectiveModel(provider, automation.getModelId()));
    }

    /** Falls back to the provider default when the stored model belongs to the other provider. */
    public static String effectiveModel(String provider, String modelId) {
        boolean anthropic = "anthropic".equalsIgnoreCase(provider);
        if (modelId == null || modelId.isBlank()) return anthropic ? DEFAULT_ANTHROPIC_MODEL : DEFAULT_OPENAI_MODEL;
        boolean claudeModel = modelId.toLowerCase(Locale.ROOT).startsWith("claude");
        if (anthropic && !claudeModel) return DEFAULT_ANTHROPIC_MODEL;
        if (!anthropic && claudeModel) return DEFAULT_OPENAI_MODEL;
        return modelId;
    }

    private Map<String, Object> runContentGeneration(AiCreds creds, Map<String, Object> config,
                                                       Map<String, String> ctx) {
        String systemPrompt = resolve(str(config, "systemPrompt",
                "You are a professional CRM AI assistant."), ctx);
        String userPrompt = resolve(str(config, "promptTemplate", "Help me with: {{task}}"), ctx);
        int maxTokens = clampTokens(intVal(config, "maxTokens", 2000));
        return callAi(creds, systemPrompt, userPrompt, maxTokens);
    }

    private Map<String, Object> runSendEmail(AiCreds creds, Map<String, Object> config,
                                              Map<String, String> ctx, Long orgId) {
        Long accountId = longVal(config, "emailAccountId");
        if (accountId == null) throw new IllegalArgumentException("emailAccountId required for send_email action");

        EmailAccount account = emailAccountRepo.findByIdAndOrganizationId(accountId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Email account not found"));
        if (!account.isActive()) throw new IllegalStateException("Email account is inactive");

        // Exactly one recipient — a substituted value can never add Cc/Bcc or extra recipients.
        String to = EmailIntegrationService.requireSingleAddress(resolve(str(config, "to", ""), ctx)).getAddress();
        String subject = resolve(str(config, "subject", "Message from CRM"), ctx);
        String bodyTemplate = str(config, "promptTemplate", null);

        String body;
        if (bodyTemplate != null && creds.hasKey()) {
            Map<String, Object> aiResult = runContentGeneration(creds, config, ctx);
            body = (String) aiResult.get("output");
        } else {
            body = resolve(str(config, "bodyTemplate", bodyTemplate != null ? bodyTemplate : ""), ctx);
        }

        emailService.sendEmail(account, to, subject, body);
        return Map.of("action", "send_email", "to", to, "subject", subject, "output", body != null ? body : "");
    }

    private Map<String, Object> runReadEmail(Map<String, Object> config, AiCreds creds, Long orgId) {
        Long accountId = longVal(config, "emailAccountId");
        if (accountId == null) throw new IllegalArgumentException("emailAccountId required for read_email action");

        EmailAccount account = emailAccountRepo.findByIdAndOrganizationId(accountId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Email account not found"));
        if (!account.isActive()) throw new IllegalStateException("Email account is inactive");

        String folder = str(config, "folder", "INBOX");
        int limit = Math.max(1, Math.min(EmailIntegrationService.MAX_READ_LIMIT, intVal(config, "limit", 10)));
        List<Map<String, Object>> emails = emailService.readEmails(account, folder, limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("emails", emails);
        result.put("count", emails.size());

        String analyzePrompt = str(config, "analyzePrompt", null);
        if (analyzePrompt != null && creds.hasKey()) {
            StringBuilder sb = new StringBuilder("Here are the recent emails:\n\n");
            for (Map<String, Object> e : emails) {
                sb.append("From: ").append(e.get("from")).append("\n");
                sb.append("Subject: ").append(e.get("subject")).append("\n");
                sb.append("Body: ").append(e.get("body")).append("\n---\n");
            }
            Map<String, Object> aiResult = callAi(creds,
                    "You are an email analysis assistant.", sb + "\n" + analyzePrompt, 2000);
            result.put("analysis", aiResult.get("output"));
            result.put("output", aiResult.get("output"));
        } else {
            result.put("output", "Read " + emails.size() + " emails from " + folder);
        }
        return result;
    }

    private Map<String, Object> runCustomApi(Map<String, Object> config, Map<String, String> ctx) {
        String urlTemplate = str(config, "url", null);
        if (urlTemplate == null || urlTemplate.isBlank()) throw new IllegalArgumentException("custom_api requires a url");
        String method = str(config, "method", "POST").toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(method)) throw new IllegalArgumentException("method must be one of " + HTTP_METHODS);

        // SSRF: the template fixes scheme/host/port; substituted values are URL-encoded and
        // the final URL is re-validated (public address, same origin). Redirects are disabled.
        URI origin = literalOrigin(urlTemplate);
        URI target = UrlSafety.requireSafe(resolveWith(urlTemplate, ctx, Encoding.URL));
        if (!sameOrigin(origin, target))
            throw new IllegalArgumentException("custom_api url must stay on the configured host");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String headersJson = str(config, "headersJson", null);
        if (headersJson != null && !headersJson.isBlank()) {
            Map<String, Object> h;
            try {
                h = objectMapper.readValue(resolveWith(headersJson, ctx, Encoding.JSON), new TypeReference<>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("headersJson must be a JSON object of string values");
            }
            if (h != null) {
                h.forEach((name, value) -> {
                    if (!HEADER_NAME.matcher(name).matches() || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("header '" + name + "' is not allowed");
                    if (!(value instanceof String v) || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0)
                        throw new IllegalArgumentException("header '" + name + "' must be a single-line string");
                    headers.set(name, v);
                });
            }
        }
        String bodyTemplate = str(config, "bodyTemplate", "");
        byte[] body = "GET".equals(method) ? new byte[0]
                : resolveWith(bodyTemplate, ctx, Encoding.JSON).getBytes(StandardCharsets.UTF_8);

        try {
            ApiResponse resp = customApiRestTemplate.execute(target, HttpMethod.valueOf(method),
                    request -> {
                        request.getHeaders().putAll(headers);
                        if (body.length > 0) request.getBody().write(body);
                    },
                    response -> new ApiResponse(response.getStatusCode().value(), readCapped(response)));
            if (resp == null) throw new IllegalStateException("custom API returned no response");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "custom_api");
            result.put("status", resp.status());
            result.put("output", resp.body());
            result.put("truncated", resp.truncated());
            return result;
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("custom API unreachable or timed out");
        }
    }

    private record ApiResponse(int status, String body, boolean truncated) {
        ApiResponse(int status, Capped c) { this(status, c.text(), c.truncated()); }
    }

    private record Capped(String text, boolean truncated) {}

    private static Capped readCapped(ClientHttpResponse response) throws java.io.IOException {
        try (InputStream in = response.getBody()) {
            byte[] buf = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            boolean truncated = buf.length > MAX_RESPONSE_BYTES;
            int len = Math.min(buf.length, MAX_RESPONSE_BYTES);
            return new Capped(new String(buf, 0, len, StandardCharsets.UTF_8), truncated);
        }
    }

    /** scheme://authority of a URL template, which must contain no {{variables}} and be public. */
    private static URI literalOrigin(String urlTemplate) {
        int schemeEnd = urlTemplate.indexOf("://");
        if (schemeEnd < 0) throw new IllegalArgumentException("custom_api url must be absolute (https://host/...)");
        int pathStart = urlTemplate.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = urlTemplate.indexOf(c, schemeEnd + 3);
            if (i >= 0 && i < pathStart) pathStart = i;
        }
        String origin = urlTemplate.substring(0, pathStart);
        if (origin.contains("{{"))
            throw new IllegalArgumentException("custom_api url scheme and host must not contain template variables");
        return UrlSafety.requireSafe(origin + "/");
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // ── AI providers ──────────────────────────────────────────────────────────

    private Map<String, Object> callAi(AiCreds creds, String systemPrompt, String userMessage, int maxTokens) {
        if (!creds.hasKey()) {
            throw new IllegalStateException("No API key configured for this automation");
        }
        return "openai".equals(creds.provider())
                ? callOpenAi(creds, systemPrompt, userMessage, maxTokens)
                : callAnthropic(creds, systemPrompt, userMessage, maxTokens);
    }

    /** Anthropic Messages API: POST /v1/messages {model, max_tokens, system, messages}. */
    private Map<String, Object> callAnthropic(AiCreds creds, String systemPrompt, String userMessage, int maxTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", creds.apiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", creds.model());
        payload.put("max_tokens", maxTokens);
        if (systemPrompt != null && !systemPrompt.isBlank()) payload.put("system", systemPrompt);
        payload.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

        JsonNode root = post(ANTHROPIC_URL, headers, payload, "Anthropic");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
        }
        int tokens = root.path("usage").path("input_tokens").asInt(0)
                + root.path("usage").path("output_tokens").asInt(0);
        return Map.of("output", text.toString(), "tokens", tokens, "model", creds.model());
    }

    /** OpenAI Chat Completions API. */
    private Map<String, Object> callOpenAi(AiCreds creds, String systemPrompt, String userMessage, int maxTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(creds.apiKey());

        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        Map<String, Object> payload = Map.of("model", creds.model(), "messages", messages, "max_tokens", maxTokens);

        JsonNode root = post(OPENAI_URL, headers, payload, "OpenAI");
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        int tokens = root.path("usage").path("total_tokens").asInt(0);
        return Map.of("output", text, "tokens", tokens, "model", creds.model());
    }

    private JsonNode post(String url, HttpHeaders headers, Map<String, Object> payload, String provider) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            ResponseEntity<String> response = aiRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payloadJson, headers), String.class);
            return objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            log.warn("{} API returned HTTP {}: {}", provider, code,
                    body.length() > 500 ? body.substring(0, 500) : body);
            if (code == 401 || code == 403)
                throw new IllegalStateException(provider + " rejected the API key (HTTP " + code + ")");
            if (code == 429) throw new IllegalStateException(provider + " rate limit reached (HTTP 429)");
            throw new IllegalStateException(provider + " API call failed (HTTP " + code + ")");
        } catch (ResourceAccessException e) {
            throw new IllegalStateException(provider + " API unreachable or timed out");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{} API call failed: {}", provider, e.toString());
            throw new IllegalStateException(provider + " API call failed");
        }
    }

    // ── Template helpers ─────────────────────────────────────────────────────

    private enum Encoding { PLAIN, URL, JSON }

    /** Plain-text substitution (prompts, subjects, bodies). Unknown variables are left as-is. */
    public static String resolve(String template, Map<String, String> vars) {
        return resolveWith(template, vars, Encoding.PLAIN);
    }

    private static String resolveWith(String template, Map<String, String> vars, Encoding enc) {
        if (template == null || vars == null) return template;
        if (enc == Encoding.JSON) {
            // A template that is just "{{var}}" becomes a complete JSON string value.
            Matcher whole = SINGLE_VAR.matcher(template);
            if (whole.matches()) {
                String v = vars.get(whole.group(1).trim());
                if (v != null) return "\"" + jsonEscape(v) + "\"";
            }
        }
        Matcher m = TEMPLATE_VAR.matcher(template);
        return m.replaceAll(match -> {
            String key = match.group(1).trim();
            String value = vars.get(key);
            if (value == null) return Matcher.quoteReplacement(match.group(0));
            String encoded = switch (enc) {
                case URL -> URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
                case JSON -> jsonEscape(value);
                case PLAIN -> value;
            };
            return Matcher.quoteReplacement(encoded);
        });
    }

    /** Escapes a value for use inside a JSON string literal (no surrounding quotes). */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<>() {});
            return m != null ? m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int clampTokens(int n) {
        return Math.max(1, Math.min(MAX_TOKENS_CAP, n));
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s ? s : def;
    }
    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }
    private static Long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        return null;
    }
}
