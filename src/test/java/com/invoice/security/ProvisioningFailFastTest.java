package com.invoice.security;

import com.invoice.tenant.SchemaProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tenant onboarding must not silently half-complete.
 *
 * notifyService() previously posted with no headers and swallowed every failure as
 * a warning, so a rejected or unreachable remote clone still reported the tenant as
 * provisioned. These tests pin both halves of the fix: the shared key is sent, and
 * remote failure propagates.
 */
class ProvisioningFailFastTest {

    private static final String KEY = "unit-test-internal-key-unit-test-internal-key-123456";

    private RestTemplate restTemplate;
    private SchemaProvisioningService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new SchemaProvisioningService(mock(DataSource.class), restTemplate);
        ReflectionTestUtils.setField(service, "internalApiKey", KEY);
        ReflectionTestUtils.setField(service, "customerServiceUrl", "http://customer:5679");
    }

    private void invokeNotify() {
        ReflectionTestUtils.invokeMethod(service, "notifyService",
                "http://customer:5679/internal/provision-schema/acme_corp", "Customer-Service");
    }

    static Stream<Object[]> remoteFailures() {
        return Stream.of(
                new Object[]{"401 Unauthorized",
                        HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                                null, null, null)},
                new Object[]{"403 Forbidden",
                        HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                                null, null, null)},
                new Object[]{"400 Bad Request",
                        HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                                null, null, null)},
                new Object[]{"500 Internal Server Error",
                        HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Server Error", null, null, null)},
                new Object[]{"503 Service Unavailable",
                        HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                                "Unavailable", null, null, null)},
                new Object[]{"connect timeout",
                        new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"))}
        );
    }

    @ParameterizedTest(name = "{0} propagates instead of being swallowed")
    @MethodSource("remoteFailures")
    @DisplayName("every remote failure mode propagates")
    void remoteFailurePropagates(String label, RuntimeException failure) {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenThrow(failure);
        IllegalStateException e = assertThrows(IllegalStateException.class, this::invokeNotify,
                label + " must not be swallowed — a half-created tenant would be reported as provisioned");
        assertTrue(e.getMessage().contains("Customer-Service"));
    }

    @Test
    @DisplayName("the shared internal key is sent on the provisioning call")
    void internalKeyIsSent() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenReturn(null);
        invokeNotify();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertEquals(KEY, captor.getValue().getHeaders().getFirst("X-Internal-Api-Key"),
                "without this header the callee now rejects the call");
    }

    @Test
    @DisplayName("a missing internal key fails before any network call")
    void missingKeyFailsClosed() {
        ReflectionTestUtils.setField(service, "internalApiKey", "");
        IllegalStateException e = assertThrows(IllegalStateException.class, this::invokeNotify);
        assertTrue(e.getMessage().contains("internal.api-key"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("the key is never written into the exception message")
    void keyNotLeakedInError() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        null, null, null));
        IllegalStateException e = assertThrows(IllegalStateException.class, this::invokeNotify);
        assertFalse(e.getMessage().contains(KEY), "the shared key must never reach an error message");
    }
}
