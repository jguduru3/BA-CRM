package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.CampaignContact;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Recipient claiming: batches of 50, disjoint under concurrency, stale claims reset, unique per number. */
class CampaignDispatcherClaimTest extends IntegrationTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaignRepository;
    @Autowired CampaignContactRepository contactRepository;

    private Campaign campaign(Long org, Long userId) {
        Campaign c = new Campaign();
        c.setOrganizationId(org);
        c.setName("claims");
        c.setType("whatsapp");
        c.setStatus("draft");            // never picked up by the background dispatcher
        c.setCreatedBy(userId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaignRepository.save(c);
    }

    private CampaignContact recipient(Campaign c, int i) {
        CampaignContact cc = new CampaignContact();
        cc.setCampaignId(c.getId());
        cc.setOrganizationId(c.getOrganizationId());
        String phone = "+9190" + String.format("%08d", i);
        cc.setPhoneNumber(phone);
        cc.setNormalizedPhone(phone);
        cc.setStatus("pending");
        cc.setCreatedAt(Instant.now());
        return cc;
    }

    @Test
    void concurrentClaimsAreDisjointAndBatched() throws Exception {
        Long org = newOrg();
        User u = user(org, Role.SUPER_ADMIN);
        Campaign c = campaign(org, u.getId());
        List<CampaignContact> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) rows.add(recipient(c, i));
        contactRepository.saveAll(rows);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                List<Long> mine = new ArrayList<>();
                List<Long> batch;
                while (!(batch = dispatcher.claimBatch(c.getId(), CampaignDispatcher.CLAIM_BATCH)).isEmpty()) {
                    assertThat(batch.size()).isLessThanOrEqualTo(CampaignDispatcher.CLAIM_BATCH);
                    mine.addAll(batch);
                }
                return mine;
            }));
        }
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> f : futures) all.addAll(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(all).hasSize(120).doesNotHaveDuplicates();
        assertThat(contactRepository.countByCampaignIdAndStatus(c.getId(), "sending")).isEqualTo(120);
        assertThat(contactRepository.findByCampaignId(c.getId())).allMatch(cc -> cc.getClaimedAt() != null);
    }

    @Test
    void staleClaimsGoBackToPendingAndResultsNeedTheClaim() {
        Long org = newOrg();
        User u = user(org, Role.SUPER_ADMIN);
        Campaign c = campaign(org, u.getId());
        contactRepository.saveAll(List.of(recipient(c, 1), recipient(c, 2)));
        List<Long> claimed = dispatcher.claimBatch(c.getId(), 50);
        assertThat(claimed).hasSize(2);

        // Result for one recipient; a second result for it is ignored (it is no longer 'sending').
        assertThat(contactRepository.markResult(claimed.get(0), "sent", "wamid.1", null, Instant.now())).isEqualTo(1);
        assertThat(contactRepository.markResult(claimed.get(0), "failed", null, "x", Instant.now())).isZero();

        // Only claims older than 15 minutes are reset.
        Instant now = Instant.now();
        assertThat(contactRepository.resetStaleClaims(now.minus(CampaignDispatcher.STALE_CLAIM), now)).isZero();
        assertThat(contactRepository.resetStaleClaims(now.plus(Duration.ofMinutes(1)), now)).isEqualTo(1);
        assertThat(contactRepository.findById(claimed.get(1)).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(contactRepository.findById(claimed.get(0)).orElseThrow().getStatus()).isEqualTo("sent");
    }

    @Test
    void sameNumberCannotBeAddedTwiceToACampaign() {
        Long org = newOrg();
        User u = user(org, Role.SUPER_ADMIN);
        Campaign c = campaign(org, u.getId());
        contactRepository.saveAndFlush(recipient(c, 7));
        assertThatThrownBy(() -> contactRepository.saveAndFlush(recipient(c, 7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
