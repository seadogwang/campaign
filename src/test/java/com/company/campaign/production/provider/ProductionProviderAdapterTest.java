package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ActionProviderException;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.action.ProviderOutcome;
import com.company.campaign.action.ProviderResult;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35C1: the deterministic, locally scripted HTTP contract of the production Provider adapters.
 *
 * <p>Every observation here is explicitly <strong>non-production evidence</strong>: a loopback scripted server
 * proves the adapters' request projection, their strict response contract, their bounds, their retry policy and
 * their redaction, and it can never satisfy a readiness level. No test in this class reads a real Provider, a
 * real credential or the developer's machine settings.
 */
class ProductionProviderAdapterTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void itSendsOnlyTheBoundedAllowlistedMessageProjection() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.successBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)))) {
            ProviderResult result = messageAdapter(server).send(
                    ProductionProviderTestSupport.messageRequest(), ProductionProviderTestSupport.KEY);

            assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(server.requests()).hasSize(1);
            ProductionProviderTestSupport.Recorded recorded = server.requests().get(0);
            assertThat(recorded.method()).isEqualTo("POST");
            assertThat(recorded.path()).isEqualTo("/provider/v1/message/effects");
            assertThat(recorded.header("content-type")).isEqualTo("application/json");
            assertThat(recorded.header("accept")).isEqualTo("application/json");
            assertThat(recorded.header("idempotency-key")).isEqualTo(ProductionProviderTestSupport.KEY);
            assertThat(recorded.header("x-campaign-contract-version"))
                    .isEqualTo(ProductionProviderProperties.CONTRACT_VERSION);
            assertThat(recorded.header("authorization"))
                    .isEqualTo("Bearer " + ProductionProviderTestSupport.CREDENTIAL);

            JsonNode body = JSON.readTree(recorded.body());
            assertThat(body.path("contractVersion").asText())
                    .isEqualTo(ProductionProviderProperties.CONTRACT_VERSION);
            assertThat(body.path("actionKind").asText()).isEqualTo("MESSAGE");
            assertThat(body.path("idempotencyKey").asText()).isEqualTo(ProductionProviderTestSupport.KEY);
            assertThat(body.path("requestId").asText()).startsWith("prvreq-");
            assertThat(body.path("requestHash").asText()).startsWith("sha256:");
            assertThat(body.path("providerRequestHash").asText()).startsWith("sha256:");
            assertThat(body.path("recipientRef").asText()).isEqualTo("recipient-1");
            // The message projection carries no reward material and no Provider-selected value at all.
            assertThat(body.has("rewardLogicalId")).isFalse();
            assertThat(body.has("rewardQuantity")).isFalse();
            assertThat(body.has("rewardFaceValue")).isFalse();
        }
    }

    @Test
    void itSendsOnlyTheBoundedAllowlistedRewardProjection() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.successBody(
                        ActionKind.REWARD, ProductionProviderTestSupport.REWARD_KEY)))) {
            ProviderResult result = rewardAdapter(server).grant(
                    ProductionProviderTestSupport.rewardRequest(), ProductionProviderTestSupport.REWARD_KEY);

            assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            ProductionProviderTestSupport.Recorded recorded = server.requests().get(0);
            assertThat(recorded.path()).isEqualTo("/provider/v1/reward/effects");
            JsonNode body = JSON.readTree(recorded.body());
            assertThat(body.path("actionKind").asText()).isEqualTo("REWARD");
            assertThat(body.path("rewardLogicalId").asText()).isEqualTo("reward-1");
            assertThat(body.path("rewardQuantity").asInt()).isEqualTo(2);
            assertThat(body.path("rewardCurrency").asText()).isEqualTo("USD");
            assertThat(body.path("rewardFaceValue").asText()).isEqualTo("10.0000");
            assertThat(body.has("channelCode")).isFalse();
            assertThat(body.has("templateLogicalId")).isFalse();
        }
    }

    @Test
    void theHeaderAndBodyKeyAreProvedEqualBeforeAnyTransport() {
        byte[] body = ProductionProviderWireContract.submissionBody(
                ProductionProviderTestSupport.messageRequest(), ProductionProviderTestSupport.KEY);

        ActionProviderException mismatch = catchThrowableOfType(
                ActionProviderException.class,
                () -> ProductionProviderWireContract.assertHeaderMatchesBody("tenant-2:other", body));
        assertThat(mismatch.code()).isEqualTo(ProductionProviderErrorCodes.PROVIDER_KEY_MISMATCH);

        ProductionProviderWireContract.assertHeaderMatchesBody(ProductionProviderTestSupport.KEY, body);
    }

    @Test
    void itConvertsAnAcceptedStrictResponseIntoTheExistingResult() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.successBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)))) {
            ProviderResult result = messageAdapter(server).send(
                    ProductionProviderTestSupport.messageRequest(), ProductionProviderTestSupport.KEY);

            assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(result.providerReference()).isEqualTo("prvref-0001");
            assertThat(result.acceptedInstant()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(result.idempotencyKey()).isEqualTo(ProductionProviderTestSupport.KEY);
        }
    }

    @Test
    void itNeverInventsSuccessFromAnHttpStatusAlone() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY,
                        ProviderOutcome.DEFINITELY_NOT_APPLIED, null, null, "PROVIDER_REJECTED")))) {
            ProviderResult result = messageAdapter(server).send(
                    ProductionProviderTestSupport.messageRequest(), ProductionProviderTestSupport.KEY);

            // HTTP 200 with a non-applied outcome stays a non-applied outcome and claims no reference.
            assertThat(result.outcome()).isEqualTo(ProviderOutcome.DEFINITELY_NOT_APPLIED);
            assertThat(result.providerReference()).isNull();
        }
    }

    // ------------------------------------------------------------------ strict response contract

    @Test
    void itRejectsAnUnknownResponseField() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBodyWith(
                        "providerDebug", "x", ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)));
    }

    @Test
    void itRejectsADuplicateJsonField() {
        String body = "{\"contractVersion\":\""
                + ProductionProviderProperties.CONTRACT_VERSION + "\",\"contractVersion\":\"other\"}";
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_MALFORMED,
                ProductionProviderTestSupport.Response.ok(body));
    }

    @Test
    void itRejectsAWrongContractVersion() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBodyWith(
                        "contractVersion", "some-other-version/9", ActionKind.MESSAGE,
                        ProductionProviderTestSupport.KEY)));
    }

    @Test
    void itRejectsAWrongActionKind() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBodyWith(
                        "actionKind", "REWARD", ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)));
    }

    @Test
    void itRejectsAWrongIdempotencyKey() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBodyWith(
                        "idempotencyKey", "tenant-9:other", ActionKind.MESSAGE,
                        ProductionProviderTestSupport.KEY)));
    }

    @Test
    void itRejectsAnUnknownOutcome() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBodyWith(
                        "outcome", "MAYBE", ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)));
    }

    @Test
    void itRejectsAReferenceOnANonAppliedOutcome() {
        String body = ProductionProviderTestSupport.responseBody(
                ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.PENDING,
                "prvref-0001", null, "PROVIDER_PENDING");
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(body));
    }

    @Test
    void itRejectsAMissingReferenceOnAnAppliedOutcome() {
        String body = ProductionProviderTestSupport.responseBody(
                ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.SUCCEEDED,
                null, null, "PROVIDER_APPLIED");
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(body));
    }

    @Test
    void itRejectsANonIdentifierReferenceAndANonMachineReason() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.SUCCEEDED,
                        "has a space", null, "PROVIDER_APPLIED")));
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.SUCCEEDED,
                        "prvref-0001", null, "not a machine code")));
    }

    @Test
    void itRejectsAnUnparseableAcceptedInstant() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTRACT_INVALID,
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.SUCCEEDED,
                        "prvref-0001", "yesterday", "PROVIDER_APPLIED")));
    }

    // ------------------------------------------------------------------ transport failures

    @Test
    void itRejectsARedirectAndNeverFollowsIt() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(302))) {
            assertThat(failureOf(server, ProductionProviderTestSupport.KEY).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_REDIRECT_REJECTED);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void itRejectsAStreamedResponse() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTENT_TYPE_INVALID,
                ProductionProviderTestSupport.Response.raw(200, "text/event-stream",
                        "data: {}\n\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void itRejectsAnUnexpectedContentType() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTENT_TYPE_INVALID,
                ProductionProviderTestSupport.Response.raw(200, "text/html",
                        "<html></html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void itRejectsMalformedJson() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_MALFORMED,
                ProductionProviderTestSupport.Response.ok("{\"contractVersion\":"));
    }

    @Test
    void itRejectsANonUtf8Body() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_NOT_UTF8,
                ProductionProviderTestSupport.Response.raw(200, "application/json",
                        new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0}));
    }

    @Test
    void itRejectsAnEmptyBody() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_EMPTY,
                ProductionProviderTestSupport.Response.ok(""));
    }

    @Test
    void itRejectsAnOversizedBody() {
        String body = ProductionProviderTestSupport.responseBody(
                ActionKind.MESSAGE, ProductionProviderTestSupport.KEY, ProviderOutcome.SUCCEEDED,
                "prvref-0001", null, "PROVIDER_APPLIED");
        String oversized = body.substring(0, body.length() - 2) + ",\"padding\":\""
                + "x".repeat(4_096) + "\"}";
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(oversized))) {
            ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                    ProductionProviderKind.MESSAGE, server.url(), 1_024, 1);
            assertThat(failureOf(properties, ProductionProviderTestSupport.KEY).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_RESPONSE_OVERSIZED);
        }
    }

    @Test
    void itRejectsRawUnsafeControlCharacters() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_RESPONSE_UNSAFE,
                ProductionProviderTestSupport.Response.raw(200, "application/json",
                        "{\"contractVersion\":\"\u0007\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void itMapsAConnectionFailureToABoundedUnknownOutcome() {
        ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                ProductionProviderKind.MESSAGE, unusedLoopbackUrl(), 65_536, 1);
        assertThat(failureOf(properties, ProductionProviderTestSupport.KEY).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
    }

    @Test
    void itMapsARequestTimeoutToABoundedUnknownOutcomeWithoutRetrying() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.delayed(2_000))) {
            ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                    ProductionProviderKind.MESSAGE, server.url());
            properties.getMessage().setRequestTimeout(Duration.ofMillis(1_000));
            properties.validate();

            assertThat(failureOf(properties, ProductionProviderTestSupport.KEY).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_TIMEOUT);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void itMapsRefusedAuthenticationAndUnknownRoutesToBoundedCodes() {
        assertCode(ProductionProviderErrorCodes.PROVIDER_AUTHENTICATION_FAILED,
                ProductionProviderTestSupport.Response.json(403));
        assertCode(ProductionProviderErrorCodes.PROVIDER_ENDPOINT_REJECTED,
                ProductionProviderTestSupport.Response.json(404));
        assertCode(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID,
                ProductionProviderTestSupport.Response.json(422));
        assertCode(ProductionProviderErrorCodes.PROVIDER_PROTOCOL_FAILED,
                ProductionProviderTestSupport.Response.json(204));
    }

    // ------------------------------------------------------------------ retry policy

    @Test
    void itNeverRetriesASubmissionInsideTheAdapter() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(500),
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.successBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)))) {
            assertThat(failureOf(server, ProductionProviderTestSupport.KEY).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_UNAVAILABLE);
            // Exactly one submission: a possibly-applied effect must be reconciled by key before any retry.
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void itRetriesAQueryWithBoundedAttemptsAndTheInjectedBackoff() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(503),
                ProductionProviderTestSupport.Response.json(500),
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.successBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY)))) {
            RecordingBackoff backoff = new RecordingBackoff();
            ProviderResult result = messageAdapter(server, 65_536, 3, backoff)
                    .queryByKey(ProductionProviderTestSupport.KEY);

            assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(server.requests()).hasSize(3);
            assertThat(backoff.attempts).containsExactly(2, 3);
        }
    }

    @Test
    void itFailsClosedAfterTheBoundedQueryAttempts() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(500),
                ProductionProviderTestSupport.Response.json(500),
                ProductionProviderTestSupport.Response.json(500))) {
            AtomicIntegerHolder attempts = new AtomicIntegerHolder();
            ProductionProviderBackoff counting = attempt -> attempts.value++;
            assertThat(failureOfQuery(server, 3, counting).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_UNAVAILABLE);
            assertThat(server.requests()).hasSize(3);
            assertThat(attempts.value).isEqualTo(2);
        }
    }

    @Test
    void itDoesNotRetryANonRetryableQueryFailure() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(401))) {
            assertThat(failureOfQuery(server, 3, ProductionProviderBackoff.none()).code())
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_AUTHENTICATION_FAILED);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void aQuerySendsOnlyTheEncodedKeyAndNeverASubmission() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok(ProductionProviderTestSupport.responseBody(
                        ActionKind.MESSAGE, ProductionProviderTestSupport.KEY,
                        ProviderOutcome.DEFINITELY_NOT_APPLIED, null, null, "PROVIDER_REJECTED")))) {
            ProviderResult result = messageAdapter(server).queryByKey(ProductionProviderTestSupport.KEY);

            assertThat(result.outcome()).isEqualTo(ProviderOutcome.DEFINITELY_NOT_APPLIED);
            ProductionProviderTestSupport.Recorded recorded = server.requests().get(0);
            assertThat(recorded.method()).isEqualTo("GET");
            assertThat(recorded.body()).isEmpty();
            assertThat(recorded.path()).isEqualTo("/provider/v1/message/effects/"
                    + java.net.URLEncoder.encode(ProductionProviderTestSupport.KEY, StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        }
    }

    // ------------------------------------------------------------------ ownership, bounds and redaction

    @Test
    void itsCapabilityWitnessesOnlyItsOwnFixedOwnerAndKind() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(500))) {
            ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                    ProductionProviderKind.MESSAGE, server.url());
            ProductionProviderMessageAdapter adapter = messageAdapter(properties);

            ProviderCapability capability = adapter.capability(ProductionProviderTestSupport.OWNER);
            assertThat(capability).isNotNull();
            assertThat(capability.ownerTenantId()).isEqualTo(ProductionProviderTestSupport.OWNER);
            assertThat(capability.actionKind()).isEqualTo(ActionKind.MESSAGE);
            assertThat(capability.production()).isFalse();
            assertThat(capability.capabilityHash())
                    .isEqualTo(properties.capabilityHash(ProductionProviderKind.MESSAGE));
            assertThat(adapter.capability("tenant-9")).isNull();
        }
    }

    @Test
    void itRefusesARequestOfAnotherKindOrKeyBeforeAnyTransport() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.ok("{}"))) {
            ProductionProviderMessageAdapter adapter = messageAdapter(server);

            ActionProviderException wrongKind = catchThrowableOfType(
                    ActionProviderException.class,
                    () -> adapter.send(ProductionProviderTestSupport.rewardRequest(), ProductionProviderTestSupport.KEY));
            assertThat(wrongKind.code()).isEqualTo(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);

            ActionProviderException wrongKey = catchThrowableOfType(
                    ActionProviderException.class,
                    () -> adapter.send(ProductionProviderTestSupport.messageRequest(), "tenant-9:other:node:MESSAGE"));
            assertThat(wrongKey.code()).isEqualTo(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);

            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void itNeverDisclosesTheCredentialEndpointRecipientOrKeyInAFailure() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(500))) {
            ActionProviderException failure = failureOf(server, ProductionProviderTestSupport.KEY);
            String described = failure.toString() + failure.getMessage();
            assertThat(described)
                    .doesNotContain(ProductionProviderTestSupport.CREDENTIAL)
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("recipient-1")
                    .doesNotContain(ProductionProviderTestSupport.KEY)
                    .doesNotContain("prvref-0001");
        }
    }

    @Test
    void itsDiagnosticCarriesOnlyFingerprintsAndTheContractVersion() {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(
                ProductionProviderTestSupport.Response.json(500))) {
            String described = messageAdapter(server).toString();
            assertThat(described)
                    .contains(ProductionProviderProperties.CONTRACT_VERSION)
                    .doesNotContain(ProductionProviderTestSupport.CREDENTIAL)
                    .doesNotContain(ProductionProviderTestSupport.PROVIDER_ID)
                    .doesNotContain(ProductionProviderTestSupport.OWNER)
                    .doesNotContain("127.0.0.1");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ProductionProviderMessageAdapter messageAdapter(
            ProductionProviderTestSupport.ScriptedServer server) {
        return messageAdapter(server, 65_536, 1, ProductionProviderBackoff.none());
    }

    private static ProductionProviderMessageAdapter messageAdapter(
            ProductionProviderTestSupport.ScriptedServer server, int maxResponseBytes, int queryMaxAttempts,
            ProductionProviderBackoff backoff) {
        ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                ProductionProviderKind.MESSAGE, server.url(), maxResponseBytes, queryMaxAttempts);
        return messageAdapter(properties, backoff);
    }

    private static ProductionProviderMessageAdapter messageAdapter(ProductionProviderProperties properties) {
        return messageAdapter(properties, ProductionProviderBackoff.none());
    }

    private static ProductionProviderMessageAdapter messageAdapter(
            ProductionProviderProperties properties, ProductionProviderBackoff backoff) {
        return new ProductionProviderMessageAdapter(
                properties,
                ProductionProviderTransport.httpClient(properties.getMessage()),
                backoff);
    }

    private static ProductionProviderRewardAdapter rewardAdapter(
            ProductionProviderTestSupport.ScriptedServer server) {
        ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                ProductionProviderKind.REWARD, server.url());
        return new ProductionProviderRewardAdapter(
                properties,
                ProductionProviderTransport.httpClient(properties.getReward()),
                ProductionProviderBackoff.none());
    }

    private void assertCode(String expectedCode, ProductionProviderTestSupport.Response response) {
        try (ProductionProviderTestSupport.ScriptedServer server = ProductionProviderTestSupport.serve(response)) {
            assertThat(failureOf(server, ProductionProviderTestSupport.KEY).code()).isEqualTo(expectedCode);
        }
    }

    private static ActionProviderException failureOf(
            ProductionProviderTestSupport.ScriptedServer server, String key) {
        ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                ProductionProviderKind.MESSAGE, server.url());
        return failureOf(properties, key);
    }

    private static ActionProviderException failureOf(ProductionProviderProperties properties, String key) {
        ProductionProviderMessageAdapter adapter = messageAdapter(properties);
        return catchThrowableOfType(
                ActionProviderException.class, () -> adapter.send(
                        ProductionProviderTestSupport.messageRequest(), key));
    }

    private static ActionProviderException failureOfQuery(
            ProductionProviderTestSupport.ScriptedServer server, int queryMaxAttempts,
            ProductionProviderBackoff backoff) {
        ProductionProviderProperties properties = ProductionProviderTestSupport.properties(
                ProductionProviderKind.MESSAGE, server.url(), 65_536, queryMaxAttempts);
        ProductionProviderMessageAdapter adapter = messageAdapter(properties, backoff);
        return catchThrowableOfType(
                ActionProviderException.class,
                () -> adapter.queryByKey(ProductionProviderTestSupport.KEY));
    }

    private static String unusedLoopbackUrl() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/";
        } catch (IOException failure) {
            throw new IllegalStateException("A free loopback port could not be found", failure);
        }
    }

    private static final class RecordingBackoff implements ProductionProviderBackoff {

        private final List<Integer> attempts = new ArrayList<>();

        @Override
        public void await(int attempt) {
            attempts.add(attempt);
        }
    }

    private static final class AtomicIntegerHolder {

        private int value;
    }
}
