package com.soulsoftworks.sockbowlquestions.ban;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable set of IP/CIDR bans matched against raw client addresses (D8,
 * AB-02; plan m4-limits section 2.4). IPv4 and IPv6 are both supported;
 * an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is matched as the IPv4
 * address it carries. Parsing never does a DNS lookup: anything that is not an
 * IP literal is rejected (or, for a client address, never matches).
 *
 * <p>{@link RedisIpBanChecker} rebuilds one of these from the Redis
 * mirror ({@code ipban:all}) and swaps it in atomically, so a lookup is a lock-free scan.
 */
public final class CidrMatcher {

    public static final CidrMatcher EMPTY = new CidrMatcher(List.of());

    /** One ban: its id, network and expiry. */
    public record Entry(String id, Cidr cidr, Instant expiresAt) {
        public Entry {
            Objects.requireNonNull(cidr, "cidr");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private final List<Entry> entries;

    private CidrMatcher(List<Entry> entries) {
        this.entries = entries;
    }

    public static CidrMatcher of(Collection<Entry> entries) {
        return entries.isEmpty() ? EMPTY : new CidrMatcher(List.copyOf(entries));
    }

    public int size() {
        return entries.size();
    }

    /**
     * @param rawAddress the client's un-truncated remote address
     * @param now        the current time; expired entries never match
     * @return the latest expiry among the active bans covering the address
     */
    public Optional<Instant> match(String rawAddress, Instant now) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        byte[] address = toBytes(rawAddress);
        if (address == null) {
            return Optional.empty();
        }
        Instant latest = null;
        for (Entry entry : entries) {
            if (entry.expiresAt().isAfter(now) && entry.cidr().contains(address)
                    && (latest == null || entry.expiresAt().isAfter(latest))) {
                latest = entry.expiresAt();
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * A network: address bytes (4 or 16, host bits cleared) and prefix length.
     */
    public record Cidr(byte[] network, int prefix) {

        /**
         * Parses {@code a.b.c.d/nn}, {@code v6/nn} or a bare address (a single
         * host: /32 or /128). Host bits are cleared, so {@code 10.1.2.3/16}
         * becomes {@code 10.1.0.0/16}.
         *
         * @throws IllegalArgumentException when it is not a valid IP literal
         *                                  with an in-range prefix
         */
        public static Cidr parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("CIDR is required");
            }
            String trimmed = text.trim();
            int slash = trimmed.indexOf('/');
            String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);
            byte[] address = toBytes(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address: " + addressPart);
            }
            int max = address.length * 8;
            int prefix = max;
            if (slash >= 0) {
                String prefixPart = trimmed.substring(slash + 1);
                if (!prefixPart.matches("\\d{1,3}")) {
                    throw new IllegalArgumentException("Invalid prefix length: " + prefixPart);
                }
                prefix = Integer.parseInt(prefixPart);
                if (prefix > max) {
                    throw new IllegalArgumentException("Prefix /" + prefix + " is out of range for "
                            + (isV4Length(address) ? "IPv4" : "IPv6"));
                }
            }
            return new Cidr(mask(address, prefix), prefix);
        }

        public boolean isV4() {
            return isV4Length(network);
        }

        public boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            byte[] masked = mask(address, prefix);
            return Arrays.equals(masked, network);
        }

        /** Canonical text: {@code 10.1.0.0/16}, {@code 2001:db8::/48}. */
        public String canonical() {
            if (!isV4()) {
                return compressV6(network) + "/" + prefix;
            }
            return (network[0] & 0xff) + "." + (network[1] & 0xff) + "." + (network[2] & 0xff) + "."
                    + (network[3] & 0xff) + "/" + prefix;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cidr other && prefix == other.prefix && Arrays.equals(network, other.network);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(network) + prefix;
        }

        @Override
        public String toString() {
            return canonical();
        }
    }

    /**
     * The address bytes of an IP literal (4 for IPv4 and IPv4-mapped IPv6, 16
     * for other IPv6), or {@code null} when it is not an IP literal.
     */
    static byte[] toBytes(String literal) {
        if (literal == null) {
            return null;
        }
        String s = literal.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.isEmpty() || !s.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        if (s.indexOf(':') < 0 && !isDottedQuad(s)) {
            // Never let InetAddress treat it as a host name (no DNS lookups).
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(s);
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                return bytes;
            }
            if (isV4Mapped(bytes)) {
                return Arrays.copyOfRange(bytes, 12, 16);
            }
            return bytes;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isDottedQuad(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isV4Length(byte[] address) {
        return address.length == 4;
    }

    private static boolean isV4Mapped(byte[] b) {
        if (b.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
            int byteMask = bitsInByte == 0 ? 0 : (0xff << (8 - bitsInByte)) & 0xff;
            out[i] = (byte) (out[i] & byteMask);
        }
        return out;
    }

    private static String compressV6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) {
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
        for (int i = 0; i < 8; i++) {
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
