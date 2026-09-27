package com.soulsoftworks.sockbowlquestions.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the client address a limit is keyed by (plan m4-limits section 2.1;
 * security-critical).
 *
 * <p>It returns {@link HttpServletRequest#getRemoteAddr()} <b>only</b>, never a
 * header. Forwarded headers are honoured solely by Tomcat's RemoteIpValve, which
 * rewrites the remote address, and only when
 * {@code server.forward-headers-strategy=native} with an explicit
 * {@code server.tomcat.remoteip.internal-proxies} regex (enforced at startup by
 * {@link LimitsStartupValidator}). Under the default strategy {@code none} a
 * spoofed {@code X-Forwarded-For} cannot mint new buckets.
 *
 * <p>Addresses are normalized: IPv4 (and IPv4-mapped IPv6) as dotted quad, IPv6
 * truncated to its /64 in RFC 5952 form with a {@code /64} suffix, so rotating
 * through one's own /64 does not reset a limit.
 */
@Component
public class ClientIpResolver {

    public static final String UNKNOWN = "unknown";

    public String resolve(HttpServletRequest request) {
        return normalize(request.getRemoteAddr());
    }

    /** The raw (un-truncated) remote address, for CIDR ban matching. */
    public String rawAddress(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null ? UNKNOWN : addr;
    }

    /**
     * Normalizes an address literal (never a host name: anything that is not an
     * IP literal is returned as {@value #UNKNOWN} without a DNS lookup).
     */
    public static String normalize(String address) {
        if (address == null) {
            return UNKNOWN;
        }
        String literal = address.trim();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.isEmpty() || !literal.matches("[0-9A-Fa-f:.]+")) {
            return UNKNOWN;
        }
        try {
            return normalize(InetAddress.getByName(literal));
        } catch (UnknownHostException e) {
            return UNKNOWN;
        }
    }

    public static String normalize(InetAddress address) {
        if (address == null) {
            return UNKNOWN;
        }
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (isIpv4Mapped(bytes)) {
                return (bytes[12] & 0xff) + "." + (bytes[13] & 0xff) + "." + (bytes[14] & 0xff) + "." + (bytes[15] & 0xff);
            }
            int[] groups = new int[8];
            for (int i = 0; i < 4; i++) {
                groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
            }
            return compress(groups) + "/64";
        }
        return UNKNOWN;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** RFC 5952: lower-case hex, no leading zeros, longest run (length at least 2) of zero groups as "::". */
    static String compress(int[] groups) {
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < groups.length; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < groups.length && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.length; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.toString();
    }
}
