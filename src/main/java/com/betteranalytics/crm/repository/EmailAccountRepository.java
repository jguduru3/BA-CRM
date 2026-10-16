package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {
    List<EmailAccount> findByOrganizationIdAndIsActiveTrue(Long orgId);
    List<EmailAccount> findByUserId(Long userId);
    Optional<EmailAccount> findByIdAndOrganizationId(Long id, Long orgId);

    /** Mailboxes the inbox poller reads. */
    List<EmailAccount> findByPollInboxTrueAndIsActiveTrue();

    long countByPollInboxTrueAndIsActiveTrue();

    long countByOrganizationIdAndPollInboxTrueAndIsActiveTrue(Long orgId);

    /** Poller bookkeeping as a targeted UPDATE (never clobbers a concurrent settings edit). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE EmailAccount a SET a.lastPolledUid = :uid, " +
           "a.lastPolledUidValidity = :validity, a.lastPolledAt = :now, a.lastPollError = :error WHERE a.id = :id")
    int recordPoll(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("uid") Long uid,
                   @org.springframework.data.repository.query.Param("validity") Long uidValidity,
                   @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                   @org.springframework.data.repository.query.Param("error") String error);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE EmailAccount a SET a.lastPolledAt = :now, " +
           "a.lastPollError = :error WHERE a.id = :id")
    int recordPollError(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                        @org.springframework.data.repository.query.Param("error") String error);
}
