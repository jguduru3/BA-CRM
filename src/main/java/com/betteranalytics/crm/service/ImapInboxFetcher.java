package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailAccount;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * IMAP implementation of {@link InboxFetcher}: INBOX opened READ_ONLY (no flags are
 * changed), UID-based (RFC 3501 UIDFolder), bounded batch, connection timeouts from
 * {@link EmailIntegrationService#connectImap}, folder and store closed in finally.
 * Messages up to 2 MB are copied in full; larger ones keep their headers only.
 */
@Component
public class ImapInboxFetcher implements InboxFetcher {

    static final int MAX_COPY_BYTES = 2 * 1024 * 1024;

    private final EmailIntegrationService emailService;

    public ImapInboxFetcher(EmailIntegrationService emailService) {
        this.emailService = emailService;
    }

    @Override
    public Batch fetchNew(EmailAccount account, Long uidValidity, Long lastUid, int max) throws Exception {
        Store store = null;
        Folder folder = null;
        try {
            store = emailService.connectImap(account);
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uf)) throw new IllegalStateException("The IMAP server does not support UIDs");
            long validity = uf.getUIDValidity();

            if (lastUid == null || uidValidity == null || uidValidity != validity) {
                return new Batch(validity, currentHighest(folder, uf), List.of());
            }
            Message[] found = uf.getMessagesByUID(lastUid + 1, UIDFolder.MAXUID);
            FetchProfile fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.SIZE);
            folder.fetch(found, fp);

            List<long[]> uids = new ArrayList<>();   // [uid, index]
            for (int i = 0; i < found.length; i++) {
                if (found[i] == null) continue;
                long uid = uf.getUID(found[i]);
                if (uid > lastUid) uids.add(new long[]{uid, i});   // "n:*" can return the last message even if uid < n
            }
            uids.sort(Comparator.comparingLong(a -> a[0]));
            List<Fetched> out = new ArrayList<>();
            long highest = lastUid;
            for (long[] u : uids) {
                if (out.size() >= max) break;
                out.add(new Fetched(u[0], detach(found[(int) u[1]])));
                highest = u[0];
            }
            return new Batch(validity, highest, out);
        } finally {
            if (folder != null && folder.isOpen()) {
                try { folder.close(false); } catch (Exception ignored) {}
            }
            if (store != null) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static long currentHighest(Folder folder, UIDFolder uf) throws MessagingException {
        long next = uf.getUIDNext();
        if (next > 0) return next - 1;
        int count = folder.getMessageCount();
        return count == 0 ? 0 : uf.getUID(folder.getMessage(count));
    }

    /** In-memory copy, readable after the folder closes. */
    private static Message detach(Message m) throws MessagingException {
        if (m instanceof MimeMessage mm && m.getSize() >= 0 && m.getSize() <= MAX_COPY_BYTES) {
            return new MimeMessage(mm);
        }
        MimeMessage headersOnly = new MimeMessage((Session) null);
        headersOnly.setText("");
        Enumeration<Header> headers = m.getAllHeaders();
        while (headers.hasMoreElements()) {
            Header h = headers.nextElement();
            if ("Content-Type".equalsIgnoreCase(h.getName())) headersOnly.setHeader(h.getName(), h.getValue());
            else headersOnly.addHeader(h.getName(), h.getValue());
        }
        return headersOnly;
    }
}
