package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void spoofedForwardedHeadersAreIgnoredUnderStrategyNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.98");
        request.addHeader("Forwarded", "for=203.0.113.97");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
        assertThat(resolver.rawAddress(request)).isEqualTo("198.51.100.20");

        // Rotating the header never changes the key.
        MockHttpServletRequest rotated = new MockHttpServletRequest();
        rotated.setRemoteAddr("198.51.100.20");
        rotated.addHeader("X-Forwarded-For", "192.0.2.1");
        assertThat(resolver.resolve(rotated)).isEqualTo(resolver.resolve(request));
    }

    @Test
    void ipv4IsKeptAsIs() {
        assertThat(ClientIpResolver.normalize("192.0.2.33")).isEqualTo("192.0.2.33");
        assertThat(ClientIpResolver.normalize(" 192.0.2.33 ")).isEqualTo("192.0.2.33");
    }

    @Test
    void ipv6IsTruncatedToItsSlash64() {
        assertThat(ClientIpResolver.normalize("2001:db8:abcd:12:1:2:3:4")).isEqualTo("2001:db8:abcd:12::/64");
        assertThat(ClientIpResolver.normalize("2001:0DB8:ABCD:0012:ffff:ffff:ffff:ffff"))
                .isEqualTo("2001:db8:abcd:12::/64");
        // Rotating within one's own /64 does not mint a new bucket.
        assertThat(ClientIpResolver.normalize("2001:db8:abcd:12::1"))
                .isEqualTo(ClientIpResolver.normalize("2001:db8:abcd:12:dead:beef:0:9"));
        // A different /64 is a different key.
        assertThat(ClientIpResolver.normalize("2001:db8:abcd:13::1")).isEqualTo("2001:db8:abcd:13::/64");
    }

    @Test
    void ipv6FormsNormalizeConsistently() {
        assertThat(ClientIpResolver.normalize("[2001:db8::1]")).isEqualTo("2001:db8::/64");
        assertThat(ClientIpResolver.normalize("fe80::1%eth0")).isEqualTo("fe80::/64");
        assertThat(ClientIpResolver.normalize("::1")).isEqualTo("::/64");
        assertThat(ClientIpResolver.normalize("2001:0:0:1:0:0:0:1")).isEqualTo("2001:0:0:1::/64");
        assertThat(ClientIpResolver.normalize("2001:db8:0:0:1::")).isEqualTo("2001:db8::/64");
        assertThat(ClientIpResolver.normalize("2001:1:0:0::")).isEqualTo("2001:1::/64");
        assertThat(ClientIpResolver.normalize("2001:1:2:3::")).isEqualTo("2001:1:2:3::/64");
    }

    @Test
    void ipv4MappedIpv6IsTreatedAsIpv4() {
        assertThat(ClientIpResolver.normalize("::ffff:192.0.2.5")).isEqualTo("192.0.2.5");
    }

    @Test
    void anythingThatIsNotAnIpLiteralIsUnknownWithoutADnsLookup() {
        assertThat(ClientIpResolver.normalize((String) null)).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(ClientIpResolver.normalize("")).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(ClientIpResolver.normalize("example.com")).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(ClientIpResolver.normalize("localhost")).isEqualTo(ClientIpResolver.UNKNOWN);
    }
}
