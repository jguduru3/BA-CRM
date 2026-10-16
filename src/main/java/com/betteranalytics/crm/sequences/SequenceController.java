package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Multi-channel sequences.
 *
 *   GET    /api/sequences                          list (everyone; SEQUENCE VIEW)
 *   GET    /api/sequences/{id}                     one sequence with its steps
 *   POST   /api/sequences                          create (Manager+)
 *   PUT    /api/sequences/{id}                     update; "steps" replaces all steps (Manager+)
 *   DELETE /api/sequences/{id}                     soft delete; live enrollments are stopped (Manager+)
 *   GET    /api/sequences/{id}/enrollments         ?status=ACTIVE&limit=200
 *   POST   /api/sequences/{id}/enrollments         {relatedType: Lead|Contact, relatedId} — EDIT on the record
 *   GET    /api/sequence-enrollments?relatedType=&relatedId=   enrollments of one record (VIEW on it)
 *   GET    /api/sequence-enrollments/{id}          detail with step executions and history
 *   POST   /api/sequence-enrollments/{id}/pause | /resume | /stop
 */
@RestController
public class SequenceController {

    private final SequenceService service;
    private final CurrentUser currentUser;

    public SequenceController(SequenceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/sequences")
    public List<Map<String, Object>> list() {
        return service.list(currentUser.get());
    }

    @GetMapping("/api/sequences/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.get(currentUser.get(), id);
    }

    @PostMapping("/api/sequences")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(service.create(currentUser.get(), body));
    }

    @PutMapping("/api/sequences/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.update(currentUser.get(), id, body);
    }

    @DeleteMapping("/api/sequences/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(currentUser.get(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/sequences/{id}/enrollments")
    public List<Map<String, Object>> enrollments(@PathVariable Long id,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer limit) {
        return service.enrollmentsForSequence(currentUser.get(), id, status, limit);
    }

    @PostMapping("/api/sequences/{id}/enrollments")
    public ResponseEntity<Map<String, Object>> enroll(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(service.enroll(currentUser.get(), id, body));
    }

    @GetMapping("/api/sequence-enrollments")
    public List<Map<String, Object>> forRecord(@RequestParam String relatedType, @RequestParam Long relatedId) {
        return service.enrollmentsForRecord(currentUser.get(), relatedType, relatedId);
    }

    @GetMapping("/api/sequence-enrollments/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.enrollmentDetail(currentUser.get(), id);
    }

    @PostMapping("/api/sequence-enrollments/{id}/{op:pause|resume|stop}")
    public Map<String, Object> transition(@PathVariable Long id, @PathVariable String op) {
        return service.transition(currentUser.get(), id, op);
    }
}
