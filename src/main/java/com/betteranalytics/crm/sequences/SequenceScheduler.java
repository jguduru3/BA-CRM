package com.betteranalytics.crm.sequences;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, one instance cluster-wide (ShedLock): run due sequence steps.
 * Row claims (SKIP LOCKED / conditional UPDATE) still guard against overlap if
 * a lock expires while a tick is running.
 */
@Component
public class SequenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(SequenceScheduler.class);

    private final SequenceEngine engine;

    public SequenceScheduler(SequenceEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${app.sequences.scheduler-delay-ms:60000}",
               initialDelayString = "${app.sequences.scheduler-initial-delay-ms:60000}")
    @SchedulerLock(name = "sequences.runDue", lockAtMostFor = "PT10M")
    public void runDue() {
        try {
            int n = engine.tick();
            if (n > 0) log.info("Sequence scheduler processed {} enrollment step(s)", n);
        } catch (Exception e) {
            log.error("Sequence scheduler tick failed: {}", e.toString());
        }
    }
}
