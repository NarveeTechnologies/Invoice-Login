package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-IP rate limiting is only as good as the address it counts. These cover
 * the two ways it fails silently: grouping every user under the gateway's
 * address, and letting a client choose its own bucket.
 */
class ClientIpResolverTest {

	private static HttpServletRequest request(String remoteAddr, String forwardedFor) {
		HttpServletRequest r = mock(HttpServletRequest.class);
		when(r.getRemoteAddr()).thenReturn(remoteAddr);
		when(r.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
		return r;
	}

	@Test
	@DisplayName("with no proxies configured the peer address is used")
	void directConnection() {
		assertEquals("203.0.113.7",
				new ClientIpResolver(0).resolveIp(request("203.0.113.7", null)));
	}

	@Test
	@DisplayName("a forwarded header is ignored when no proxy is configured")
	void headerIgnoredWhenNotBehindProxy() {
		// Otherwise any direct caller could pick their own rate-limit bucket.
		assertEquals("203.0.113.7",
				new ClientIpResolver(0).resolveIp(request("203.0.113.7", "1.1.1.1")));
	}

	@Test
	@DisplayName("behind two proxies the client-supplied prefix is not trusted")
	void spoofedPrefixIsIgnored() {
		// The attacker sends X-Forwarded-For: 9.9.9.9. nginx appends the real
		// client, the gateway appends nginx. Counting from the right past our
		// two hops lands on the address nginx observed, not the forged one.
		HttpServletRequest r = request("172.18.0.9", "9.9.9.9, 203.0.113.7, 172.18.0.2");
		assertEquals("203.0.113.7", new ClientIpResolver(2).resolveIp(r),
				"must not resolve to the client-supplied 9.9.9.9");
	}

	@Test
	@DisplayName("a rotating forged prefix cannot produce a fresh bucket each time")
	void forgedPrefixCannotEvadeTheLimit() {
		ClientIpResolver resolver = new ClientIpResolver(2);
		String first = resolver.resolveIp(
				request("172.18.0.9", "1.1.1.1, 203.0.113.7, 172.18.0.2"));
		String second = resolver.resolveIp(
				request("172.18.0.9", "2.2.2.2, 203.0.113.7, 172.18.0.2"));
		assertEquals(first, second,
				"changing the forged entry changed the bucket — the ceiling is evadable");
	}

	@Test
	@DisplayName("a chain shorter than configured falls back to the peer")
	void shortChainFallsBack() {
		// Every entry would be client-supplied, so none can be trusted.
		assertEquals("172.18.0.9",
				new ClientIpResolver(3).resolveIp(request("172.18.0.9", "1.1.1.1")));
	}

	@Test
	@DisplayName("a missing header behind a proxy falls back to the peer")
	void missingHeaderFallsBack() {
		assertEquals("172.18.0.9",
				new ClientIpResolver(2).resolveIp(request("172.18.0.9", null)));
	}

	@Test
	@DisplayName("whitespace in the header does not shift the index")
	void whitespaceTolerated() {
		HttpServletRequest r = request("172.18.0.9", "  9.9.9.9 ,  203.0.113.7 , 172.18.0.2  ");
		assertEquals("203.0.113.7", new ClientIpResolver(2).resolveIp(r));
	}
}
