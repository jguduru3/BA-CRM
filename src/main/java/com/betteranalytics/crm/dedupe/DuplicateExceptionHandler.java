package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.security.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {"error","code","requestId"} like every other error, plus the object type,
 * whether a bypass (allowDuplicate=true) is possible, and the candidate list.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DuplicateExceptionHandler {

    @ExceptionHandler(DuplicateFoundException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateFoundException e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", e.getMessage());
        b.put("code", e.code());
        String rid = MDC.get(RequestIdFilter.MDC_KEY);
        if (rid != null) b.put("requestId", rid);
        b.put("type", e.getType().name().toLowerCase(Locale.ROOT));
        b.put("canBypass", !e.isBlocked());
        b.put("candidates", e.getCandidates());
        return ResponseEntity.status(409).body(b);
    }
}
