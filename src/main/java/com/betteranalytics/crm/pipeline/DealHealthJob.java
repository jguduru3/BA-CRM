package com.betteranalytics.crm.pipeline;

import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.repository.OpportunityStageHistoryRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Nightly: recompute the health score of every open deal, raise risk flags and
 * notify the owner + manager once per newly raised flag.
 *
 * Each deal is handled in its own short transaction. The score is stored with a
 * conditional bulk UPDATE (… WHERE health_flags = what we read) that does not
 * bump the optimistic-lock version, so overlapping runs or a concurrent edit never
 * double-notify and never make an open edit form stale.
 *
 * The run also repairs data written by paths that skip the controller: deals with
 * no stage-history row get their initial row, and deals with no forecast category
 * get the one their stage maps to.
 */
@Service
public class DealHealthJob {

    private static final Logger log = LoggerFactory.getLogger(DealHealthJob.class);
    static final int BATCH_SIZE = 500;

    private final OpportunityRepository opportunityRepository;
    private final OpportunityStageHistoryRepository historyRepository;
    private final DealHealthService health;
    private final PipelineSettingsService pipelineSettings;
    private final TransactionTemplate tx;

    public DealHealthJob(OpportunityRepository opportunityRepository, OpportunityStageHistoryRepository historyRepository,
                         DealHealthService health, PipelineSettingsService pipelineSettings,
                         PlatformTransactionManager transactionManager) {
        this.opportunityRepository = opportunityRepository;
        this.historyRepository = historyRepository;
        this.health = health;
        this.pipelineSettings = pipelineSettings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${app.pipeline.health-cron:0 30 2 * * *}")
    @SchedulerLock(name = "opportunities.dealHealth", lockAtMostFor = "PT30M")
    public void scheduled() {
        recomputeAll(Instant.now());
    }

    /** @return how many risk notifications were sent */
    public int recomputeAll(Instant now) {
        repair();
        List<String> closed = List.copyOf(PipelineSettingsService.CLOSED_STAGES);
        tx.executeWithoutResult(s -> opportunityRepository.clearHealthOfClosed(closed));

        Map<Long, Map<String, Double>> benchmarks = new HashMap<>();
        int sent = 0;
        long after = 0;
        while (true) {
            List<Opportunity> batch = opportunityRepository.findOpenAfter(closed, after, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) break;
            for (Opportunity o : batch) {
                after = o.getId();
                try {
                    Map<String, Double> bm = benchmarks.computeIfAbsent(o.getOrganizationId(), health::benchmarks);
                    Integer n = tx.execute(s -> recompute(o, now, bm));
                    if (n != null) sent += n;
                } catch (Exception e) {
                    log.warn("Deal health recompute failed for opportunity id={}: {}", o.getId(), e.getClass().getSimpleName());
                }
            }
            if (batch.size() < BATCH_SIZE) break;
        }
        if (sent > 0) log.info("Deal health: sent {} risk notification(s)", sent);
        return sent;
    }

    private int recompute(Opportunity o, Instant now, Map<String, Double> bm) {
        DealHealthService.Health h = health.evaluate(o, now, bm);
        String oldFlags = o.getHealthFlags() == null ? "" : o.getHealthFlags();
        int updated = opportunityRepository.updateHealth(o.getId(), h.score(),
                h.score() == null ? null : health.factorsJson(h),
                DealHealthService.flagsString(h.flags().keySet()), now, oldFlags);
        if (updated != 1) return 0; // changed underneath us: the next run picks it up
        return health.notifyRaised(o, DealHealthService.parseFlags(o.getHealthFlags()), h, null);
    }

    private void repair() {
        try {
            Integer rows = tx.execute(s -> historyRepository.backfillMissing());
            if (rows != null && rows > 0) log.info("Backfilled stage history for {} deal(s)", rows);
            while (true) {
                List<Opportunity> missing = opportunityRepository.findWithoutForecastCategory(PageRequest.of(0, BATCH_SIZE));
                if (missing.isEmpty()) break;
                tx.executeWithoutResult(s -> {
                    for (Opportunity o : missing) {
                        opportunityRepository.fillForecastCategory(o.getId(),
                                pipelineSettings.get(o.getOrganizationId()).categoryFor(o.getStage()));
                    }
                });
                if (missing.size() < BATCH_SIZE) break;
            }
        } catch (Exception e) {
            log.warn("Pipeline data repair failed: {}", e.getClass().getSimpleName());
        }
    }
}
