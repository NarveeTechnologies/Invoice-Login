package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.mockito.ArgumentCaptor;

import com.invoice.exception.MailDeliveryException;
import com.invoice.mail.EmailNotificationService;

class OtpServiceTest {

	/**
	 * A transaction manager that does nothing but let the callback run.
	 * OtpService drives TransactionTemplate directly rather than relying on
	 * @Transactional, so unit tests need a manager but not a database.
	 */
	static PlatformTransactionManager noopTransactions() {
		PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
		when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		return tm;
	}


	private static final String EMAIL = "user@example.com";
	private static final OtpRequestContext CONTEXT =
			new OtpRequestContext("203.0.113.7", "junit");

	private OtpChallengeRepository repository;
	private OtpRateLimiter rateLimiter;
	private OtpAuditLogger audit;
	private EmailNotificationService mail;
	private OtpHasher hasher;
	private OtpProperties properties;
	private OtpService service;

	@BeforeEach
	void setUp() {
		properties = new OtpProperties();
		properties.setPepper("unit-test-pepper-at-least-32-bytes-long!");
		hasher = new OtpHasher(properties);
		repository = mock(OtpChallengeRepository.class);
		rateLimiter = mock(OtpRateLimiter.class);
		audit = mock(OtpAuditLogger.class);
		mail = mock(EmailNotificationService.class);
		when(mail.providerName()).thenReturn("smtp");
		when(rateLimiter.check(anyString(), any(), any(), any()))
				.thenReturn(OtpRateLimitDecision.allow());
		when(repository.insert(anyString(), any(), anyString(), any(), anyInt(),
				any(), any(), any(), anyBoolean(), any())).thenReturn(1L);

		service = new OtpService(repository, new OtpCodeGenerator(), hasher,
				rateLimiter, audit, mail, properties, noopTransactions());
	}

	// ---- request ----------------------------------------------------------

	@Test
	@DisplayName("a known address is sent a code")
	void knownAddressIsSent() {
		service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);
		verify(mail).sendOtp(eq(EMAIL), eq(OtpPurpose.LOGIN), anyString(), any(), any());
	}

	@Test
	@DisplayName("an unknown address gets no mail but is otherwise indistinguishable")
	void unknownAddressIsNotMailed() {
		// The row is still written, so the request counts against the ceilings
		// and an operator can see the attempt; no mail goes to a third party.
		assertDoesNotThrow(() -> service.request(EMAIL, OtpPurpose.LOGIN,
				identifier -> false, CONTEXT));
		verify(repository).insert(anyString(), eq(OtpPurpose.LOGIN), anyString(), any(),
				anyInt(), any(), any(), any(), eq(false), any());
		verifyNoInteractions(mail);
	}

	@Test
	@DisplayName("registration mails an address that has no account yet")
	void registrationMailsUnknownAddress() {
		// The one purpose where absence of an account is the normal case.
		service.request(EMAIL, OtpPurpose.REGISTRATION, identifier -> false, CONTEXT);
		verify(mail).sendOtp(eq(EMAIL), eq(OtpPurpose.REGISTRATION), anyString(), any(), any());
	}

	@Test
	@DisplayName("a rate-limited request never reaches the mail server or the table")
	void rateLimitedRequestStops() {
		when(rateLimiter.check(anyString(), any(), any(), any()))
				.thenReturn(OtpRateLimitDecision.deny("resend_cooldown", Duration.ofSeconds(45)));

		OtpRateLimitedException e = assertThrows(OtpRateLimitedException.class,
				() -> service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT));

		assertEquals(45, e.getRetryAfterSeconds());
		verifyNoInteractions(mail);
		verify(repository, never()).insert(anyString(), any(), anyString(), any(), anyInt(),
				any(), any(), any(), anyBoolean(), any());
	}

	@Test
	@DisplayName("the rate-limit message names no ceiling")
	void rateLimitMessageLeaksNothing() {
		// Telling a caller which ceiling they hit tells them how to spread the
		// next attempt.
		OtpRateLimitedException e = new OtpRateLimitedException(Duration.ofMinutes(15));
		String msg = e.getMessage().toLowerCase();
		assertAll(
				() -> assertFalse(msg.contains("ip")),
				() -> assertFalse(msg.contains("identifier")),
				() -> assertFalse(msg.contains("cooldown")));
	}

	@Test
	@DisplayName("a resend retires the previous challenge before issuing a new one")
	void resendInvalidatesPredecessor() {
		service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);
		verify(repository).invalidateLive(anyString(), eq(OtpPurpose.LOGIN),
				eq(OtpInvalidationReason.SUPERSEDED), any());
	}

	@Test
	@DisplayName("a delivery failure propagates to the caller")
	void deliveryFailurePropagates() {
		// The caller must not be told a code is on its way when none is.
		doThrow(new MailDeliveryException("could not send", new RuntimeException("smtp 535")))
				.when(mail).sendOtp(anyString(), any(), anyString(), any(), any());

		assertThrows(MailDeliveryException.class,
				() -> service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT));
		verify(audit).record(eq(OtpAuditEvent.OTP_DELIVERY_FAILED), any(), any(),
				anyString(), any(), anyString());
	}

	@Test
	@DisplayName("a delivery failure retires the challenge explicitly, not by rollback")
	void deliveryFailureInvalidatesTheChallenge() {
		// The send no longer runs inside the insert's transaction, so a rollback
		// cannot undo it any more. An undelivered passcode must still not stay
		// valid, so it is retired by an explicit second write.
		doThrow(new MailDeliveryException("could not send", new RuntimeException("smtp 535")))
				.when(mail).sendOtp(anyString(), any(), anyString(), any(), any());

		assertThrows(MailDeliveryException.class,
				() -> service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT));

		verify(repository).invalidateById(eq(1L),
				eq(OtpInvalidationReason.DELIVERY_FAILED), any());
	}

	@Test
	@DisplayName("no database work happens while the mail server is being talked to")
	void noDatabaseWorkDuringSend() {
		// The defect this replaced: the send sat inside the transaction that
		// inserted the challenge, so every in-flight request pinned a Hikari
		// connection for the whole SMTP exchange. Against a stalled relay,
		// eleven concurrent sign-ins exhausted a pool of ten and took every
		// database-backed request in the service down with them.
		java.util.concurrent.atomic.AtomicInteger atSendTime =
				new java.util.concurrent.atomic.AtomicInteger(-1);

		doAnswer(invocation -> {
			atSendTime.set(mockingDetails(repository).getInvocations().size());
			return null;
		}).when(mail).sendOtp(anyString(), any(), anyString(), any(), any());

		int beforeRequest = mockingDetails(repository).getInvocations().size();
		service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);
		int afterRequest = mockingDetails(repository).getInvocations().size();

		verify(mail).sendOtp(anyString(), any(), anyString(), any(), any());
		assertTrue(atSendTime.get() > beforeRequest,
				"the challenge should be persisted and committed before the send");
		assertEquals(atSendTime.get(), afterRequest,
				"the repository was touched after the send began, so a connection "
						+ "was being held across SMTP");
	}

	@Test
	@DisplayName("the stored hash is not the code that was mailed")
	void storedFormIsHashed() {
		service.request(EMAIL, OtpPurpose.LOGIN, identifier -> true, CONTEXT);

		ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
		verify(mail).sendOtp(anyString(), any(), mailed.capture(), any(), any());
		ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
		verify(repository).insert(anyString(), any(), stored.capture(), any(), anyInt(),
				any(), any(), any(), anyBoolean(), any());

		assertNotEquals(mailed.getValue(), stored.getValue());
		assertEquals(hasher.hashCode(OtpPurpose.LOGIN, EMAIL, mailed.getValue()),
				stored.getValue(), "the stored value must be the MAC of the mailed code");
	}

	// ---- verify -----------------------------------------------------------

	private OtpChallenge challenge(String code, OtpPurpose purpose, Instant expiresAt,
			Instant consumedAt, int attempts, int maxAttempts,
			Instant invalidatedAt, String invalidatedReason) {
		return new OtpChallenge(1L, hasher.hashIdentifier(EMAIL), purpose,
				hasher.hashCode(purpose, EMAIL, code), expiresAt, consumedAt,
				attempts, maxAttempts, invalidatedAt, invalidatedReason,
				Instant.now(), UUID.randomUUID(), true);
	}

	@Test
	@DisplayName("the right code verifies and is spent")
	void correctCodeVerifies() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), null, 0, 5, null, null)));
		when(repository.consume(eq(1L), any())).thenReturn(true);

		assertTrue(service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).isVerified());
		verify(repository).consume(eq(1L), any());
	}

	@Test
	@DisplayName("a wrong code fails and charges an attempt")
	void wrongCodeChargesAnAttempt() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), null, 0, 5, null, null)));
		when(repository.recordFailedAttempt(eq(1L), any())).thenReturn(4);

		OtpVerificationResult result = service.verify(EMAIL, OtpPurpose.LOGIN, "ZZZ999", CONTEXT);
		assertEquals(OtpVerificationOutcome.WRONG_CODE, result.outcome());
		assertEquals(4, result.attemptsRemaining());
		verify(repository).recordFailedAttempt(eq(1L), any());
	}

	@Test
	@DisplayName("malformed input costs no attempt and does not touch the database")
	void malformedCostsNothing() {
		OtpVerificationResult result = service.verify(EMAIL, OtpPurpose.LOGIN, "!!!", CONTEXT);
		assertEquals(OtpVerificationOutcome.MALFORMED, result.outcome());
		verify(repository, never()).lockLatest(anyString(), any());
		verify(repository, never()).recordFailedAttempt(anyLong(), any());
	}

	@Test
	@DisplayName("a code issued for another purpose does not verify")
	void purposeConfusionRefused() {
		// The old table had one row per address and no purpose, so a bank-change
		// code satisfied a sign-in.
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.empty());

		assertEquals(OtpVerificationOutcome.NO_CHALLENGE,
				service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).outcome());
	}

	@Test
	@DisplayName("an expired code does not verify and is not consumed")
	void expiredRefused() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().minusSeconds(1), null, 0, 5, null, null)));

		assertEquals(OtpVerificationOutcome.EXPIRED,
				service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).outcome());
		verify(repository, never()).consume(anyLong(), any());
	}

	@Test
	@DisplayName("a already-spent code is refused as a replay, even with the right code")
	void replayRefused() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), Instant.now(), 0, 5, null, null)));

		assertEquals(OtpVerificationOutcome.ALREADY_CONSUMED,
				service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).outcome());
		verify(repository, never()).consume(anyLong(), any());
	}

	@Test
	@DisplayName("an exhausted challenge is refused before the code is even compared")
	void exhaustedRefused() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), null, 5, 5, null, null)));

		assertEquals(OtpVerificationOutcome.ATTEMPTS_EXHAUSTED,
				service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).outcome());
		verify(repository, never()).consume(anyLong(), any());
	}

	@Test
	@DisplayName("a superseded challenge is refused even with its own correct code")
	void supersededRefused() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), null, 0, 5,
						Instant.now(), OtpInvalidationReason.SUPERSEDED.name())));

		assertEquals(OtpVerificationOutcome.INVALIDATED,
				service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT).outcome());
	}

	@Test
	@DisplayName("losing the consume race is reported as a replay, not a success")
	void lostConsumeRaceIsNotSuccess() {
		when(repository.lockLatest(anyString(), eq(OtpPurpose.LOGIN)))
				.thenReturn(Optional.of(challenge("ABC234", OtpPurpose.LOGIN,
						Instant.now().plusSeconds(600), null, 0, 5, null, null)));
		when(repository.consume(eq(1L), any())).thenReturn(false);

		OtpVerificationResult result = service.verify(EMAIL, OtpPurpose.LOGIN, "ABC234", CONTEXT);
		assertFalse(result.isVerified());
		assertEquals(OtpVerificationOutcome.ALREADY_CONSUMED, result.outcome());
	}

	@Test
	@DisplayName("every failure renders the same message")
	void failuresAreIndistinguishableToTheCaller() {
		String message = OtpVerificationResult.userFacingFailureMessage();
		String lower = message.toLowerCase();
		assertAll(
				() -> assertFalse(lower.contains("expired") && lower.contains("not found"),
						"the message must not enumerate the distinct causes"),
				() -> assertFalse(lower.contains("no otp")),
				() -> assertFalse(lower.contains("attempts")),
				() -> assertFalse(lower.contains("registered")));
	}
}
