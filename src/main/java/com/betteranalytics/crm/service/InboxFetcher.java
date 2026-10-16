package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailAccount;
import jakarta.mail.Message;

import java.util.List;

/**
 * Reads new INBOX messages of one mailbox (default: {@link ImapInboxFetcher}).
 * Tests replace this bean with a fake mail store.
 */
public interface InboxFetcher {

    /** One message, detached from the server (readable after the folder is closed). */
    record Fetched(long uid, Message message) {}

    /**
     * @param uidValidity UIDVALIDITY of the folder these UIDs belong to
     * @param highestUid  new high-water mark (the largest UID covered by this read)
     * @param messages    messages with lastUid &lt; uid &le; highestUid, ascending, at most {@code max}
     */
    record Batch(long uidValidity, long highestUid, List<Fetched> messages) {}

    /**
     * Messages that arrived after {@code lastUid}. When there is no baseline yet
     * ({@code lastUid} null) or the folder's UIDVALIDITY changed, returns no messages
     * and the current high-water mark, so history is never replayed.
     */
    Batch fetchNew(EmailAccount account, Long uidValidity, Long lastUid, int max) throws Exception;
}
