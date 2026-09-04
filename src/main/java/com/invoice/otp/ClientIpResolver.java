package com.invoice.otp;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Works out which address to rate-limit a request against.
 *
 * <p>This needs care, and getting it wrong fails silently in one of two
 * directions.
 *
 * <p>Invoice-Login never sees a browser directly. The chain is
 * browser to nginx to gateway to login, so {@code getRemoteAddr()} is always
 * the gateway's container address — the same value for every user on the
 * platform. Rate limiting on it would put the whole world in one bucket, and
 * the per-IP ceiling would start refusing legitimate traffic the moment the
 * platform had more than a handful of users.
 *
 * <p>The obvious repair, taking the first entry of {@code X-Forwarded-For}, is
 * worse. That header is appended to by each hop, and the leftmost entry is
 * whatever the original client sent — an attacker simply supplies a fresh one
 * per request and the ceiling never applies to them at all. A limit that an
 * attacker can opt out of while honest users cannot is not a limit.
 *
 * <p>So the address is counted from the right. With {@code trusted-proxy-count}
 * hops in front of this service, the last that many entries were written by
 * infrastructure we control, and the one immediately before them is the address
 * the outermost trusted proxy actually observed. That entry cannot be forged by
 * the client, because nginx overwrote whatever position it would have occupied.
 *
 * <p>Set {@code invoice.otp.trusted-proxy-count} to the number of reverse
 * proxies in front of this service — 2 for the deployed nginx-plus-gateway
 * topology, 0 when it is reached directly. Getting it too high resolves to a
 * proxy address and over-groups; too low trusts a client-supplied value.
 */
@Component
public class ClientIpResolver {

	private static final String FORWARDED_FOR = "X-Forwarded-For";

	private final int trustedProxyCount;

	public ClientIpResolver(
			@Value("${invoice.otp.trusted-proxy-count:0}") int trustedProxyCount) {
		this.trustedProxyCount = Math.max(0, trustedProxyCount);
	}

	public OtpRequestContext contextOf(HttpServletRequest request) {
		if (request == null) {
			return OtpRequestContext.none();
		}
		return new OtpRequestContext(resolveIp(request), request.getHeader("User-Agent"));
	}

	String resolveIp(HttpServletRequest request) {
		if (trustedProxyCount == 0) {
			return request.getRemoteAddr();
		}

		String header = request.getHeader(FORWARDED_FOR);
		if (header == null || header.isBlank()) {
			// No header despite an expected proxy: the peer is the best we have.
			return request.getRemoteAddr();
		}

		List<String> hops = List.of(header.split(",")).stream()
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();

		// The chain reaching us is: [client] [proxy1] ... [proxyN-1], with the
		// final hop appearing as the peer rather than in the header. Index from
		// the right, past the entries our own infrastructure contributed.
		int index = hops.size() - trustedProxyCount;
		if (index < 0) {
			// Shorter chain than configured — every entry is client-supplied and
			// none can be trusted. Fall back to the peer, which cannot be forged.
			return request.getRemoteAddr();
		}
		if (index >= hops.size()) {
			return hops.get(hops.size() - 1);
		}
		return hops.get(index);
	}
}
