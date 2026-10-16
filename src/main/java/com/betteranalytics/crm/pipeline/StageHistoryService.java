package com.betteranalytics.crm.pipeline;

import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.OpportunityStageHistory;
import com.betteranalytics.crm.repository.OpportunityStageHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Writes the append-only opportunity stage history: one row when a deal is
 * created and one per stage change. Each change row carries how long the deal
 * spent in the stage it left and how long it has existed, so velocity reports
 * are plain SQL averages.
 */
@Service
public class StageHistoryService {

    private final OpportunityStageHistoryRepository repository;

    public StageHistoryService(OpportunityStageHistoryRepository repository) {
        this.repository = repository;
    }

    public OpportunityStageHistory recordCreated(Opportunity opp, Long actorId) {
        OpportunityStageHistory h = base(opp, actorId,
                opp.getStageEnteredAt() != null ? opp.getStageEnteredAt()
                        : opp.getCreatedAt() != null ? opp.getCreatedAt() : Instant.now());
        h.setToStage(opp.getStage());
        h.setSecondsSinceCreated(0L);
        return repository.save(h);
    }

    /**
     * Records a stage change at {@code at}. {@code previousStageEnteredAt} is when
     * the deal entered {@code fromStage} (falls back to the latest history row,
     * then to the deal's creation).
     */
    public OpportunityStageHistory recordChange(Opportunity opp, String fromStage, Instant previousStageEnteredAt,
                                                Long actorId, Instant at) {
        Instant enteredFrom = previousStageEnteredAt;
        if (enteredFrom == null) {
            enteredFrom = repository.findFirstByOpportunityIdOrderByEnteredAtDescIdDesc(opp.getId())
                    .map(OpportunityStageHistory::getEnteredAt).orElse(opp.getCreatedAt());
        }
        OpportunityStageHistory h = base(opp, actorId, at);
        h.setFromStage(fromStage);
        h.setToStage(opp.getStage());
        if (enteredFrom != null) h.setSecondsInFromStage(Math.max(0L, Duration.between(enteredFrom, at).getSeconds()));
        if (opp.getCreatedAt() != null) h.setSecondsSinceCreated(Math.max(0L, Duration.between(opp.getCreatedAt(), at).getSeconds()));
        return repository.save(h);
    }

    private static OpportunityStageHistory base(Opportunity opp, Long actorId, Instant at) {
        OpportunityStageHistory h = new OpportunityStageHistory();
        h.setOpportunityId(opp.getId());
        h.setOrganizationId(opp.getOrganizationId());
        h.setChangedBy(actorId);
        h.setEnteredAt(at);
        return h;
    }
}
