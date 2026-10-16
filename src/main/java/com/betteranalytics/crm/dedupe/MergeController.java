package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/{leads|contacts|companies}/{primaryId}/merge} with body
 * {@code {"secondaryIds": [..]}} (at most 2). Manager+, EDIT on every record. Irreversible.
 */
@RestController
@RequestMapping("/api")
public class MergeController {

    private final MergeService mergeService;
    private final CurrentUser currentUser;

    public MergeController(MergeService mergeService, CurrentUser currentUser) {
        this.mergeService = mergeService;
        this.currentUser = currentUser;
    }

    @PostMapping("/leads/{id}/merge")
    public Map<String, Object> mergeLeads(@PathVariable Long id, @RequestBody JsonNode body) {
        return mergeService.merge(currentUser.get(), ObjectType.LEAD, id, secondaryIds(body));
    }

    @PostMapping("/contacts/{id}/merge")
    public Map<String, Object> mergeContacts(@PathVariable Long id, @RequestBody JsonNode body) {
        return mergeService.merge(currentUser.get(), ObjectType.CONTACT, id, secondaryIds(body));
    }

    @PostMapping("/companies/{id}/merge")
    public Map<String, Object> mergeCompanies(@PathVariable Long id, @RequestBody JsonNode body) {
        return mergeService.merge(currentUser.get(), ObjectType.COMPANY, id, secondaryIds(body));
    }

    private static List<Long> secondaryIds(JsonNode body) {
        JsonNode arr = body == null ? null : body.get("secondaryIds");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("secondaryIds must be an array of ids");
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.canConvertToLong() || n.isBoolean()) throw new IllegalArgumentException("secondaryIds must be an array of ids");
            ids.add(n.asLong());
        }
        return ids;
    }
}
