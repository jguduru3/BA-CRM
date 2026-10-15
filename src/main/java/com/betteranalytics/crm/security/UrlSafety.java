package com.betteranalytics.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard for every server-side request to a user-supplied URL (outbound
 * webhooks, AI automation "custom API" actions, ...). Only http(s) is allowed,
 * and every address the host resolves to must be public — loopback, private,
 * link-local (incl. cloud metadata 169.254.169.254), CGNAT, multicast and
 * unspecified ranges are rejected. Callers must also disable redirects, since
 * a redirect target is not re-validated by the HTTP client.
 *
 * app.outbound.allow-private-networks=true relaxes the address check for local
 * development (e.g. pointing a webhook at a localhost receiver).
 */
@Component
public class UrlSafety {

    private static volatile boolean allowPrivate = false;

    public UrlSafety(@Value("${app.outbound.allow-private-networks:false}") boolean allowPrivateNetworks) {
        allowPrivate = allowPrivateNetworks;
    }

    /** @throws IllegalArgumentException with a user-facing reason when the URL is not allowed. */
    public static URI requireSafe(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("url must include a host");
        if (allowPrivate) return uri;

        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal")
                || lower.endsWith(".local")) {
            throw new IllegalArgumentException("url must point to a public host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host could not be resolved");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) throw new IllegalArgumentException("url must point to a public host");
        }
        return uri;
    }

    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return false;                                  // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;       // 100.64.0.0/10 CGNAT
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;      // benchmarking
            if (b0 >= 224) return false;                                // multicast + reserved
        } else if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return false;                      // fc00::/7 unique local
            // IPv4-mapped (::ffff:a.b.c.d) — check the embedded IPv4 address
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }
}
