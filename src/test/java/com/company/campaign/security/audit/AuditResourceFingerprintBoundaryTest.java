package com.company.campaign.security.audit;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.security.authorization.FailClosedAuthorizationService;
import com.company.campaign.security.authorization.OwnedResourceRef;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermissions;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Regression tests for T02-R2: an audit resource is a non-reversible fingerprint, a readable reference
 * cannot be represented, and every bounded audit field fails closed without echoing rejected text.
 */
class AuditResourceFingerprintBoundaryTest {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T02:00:01Z");

    private final CanonicalJson canonicalJson = new CanonicalJson();

    /** Requirement 1: the serialized fingerprint has a kind and lowercase canonical hash, not the raw id. */
    @Test
    void serializedFingerprintCarriesKindAndLowercaseHashOnly() {
        OwnedResourceRef resource = new OwnedResourceRef(
                ResourceKind.SEGMENT, "member-42", "tenant-001", "program-001");

        String rendered = canonicalJson.writeString(AuditResourceFingerprint.of(resource));

        assertThat(rendered).contains("\"kind\":\"SEGMENT\"");
        assertThat(rendered).containsPattern("\"hash\":\"sha256:[0-9a-f]{64}\"");
        assertThat(rendered).doesNotContain("member-42", "tenant-001", "program-001");
    }

    /** Requirement 2: sensitive and foreign identifiers never appear in a serialized audit event. */
    @Test
    void sensitiveAndForeignIdentifiersNeverAppearInSerializedAuditEvents() {
        List<String> sensitive = List.of(
                "member-42",
                "tenant-999",
                "program-999",
                "a@example.com",
                "+8613800000000",
                "Bearer abc123",
                "p@ssw0rd");

        for (String raw : sensitive) {
            OwnedResourceRef resource = new OwnedResourceRef(ResourceKind.SEGMENT, raw, raw, raw);
            SecurityAuditEvent event = new SecurityAuditEvent(
                    SecurityAction.RESOURCE_AUTHORIZATION,
                    SecurityDisposition.ALLOWED,
                    "AUTHORIZATION_ALLOWED",
                    OCCURRED_AT,
                    0L,
                    "tenant-001", "program-001", "user-001", "scope-008",
                    AuditResourceFingerprint.of(resource),
                    null);

            String rendered = canonicalJson.writeString(event);
            assertThat(rendered).as("raw token '%s' must not be serialized", raw).doesNotContain(raw);
        }
    }

    /** Requirement 3: two equal server-owned identities produce equal fingerprints. */
    @Test
    void equalServerOwnedResourceIdentitiesProduceEqualFingerprints() {
        OwnedResourceRef first = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");
        OwnedResourceRef second = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");

        assertThat(AuditResourceFingerprint.of(first)).isEqualTo(AuditResourceFingerprint.of(second));
        assertThat(AuditResourceFingerprint.of(first).hash()).isEqualTo(AuditResourceFingerprint.of(second).hash());
    }

    /** Requirement 4: changing the kind or any identity field changes the fingerprint. */
    @Test
    void changingKindOrAnyIdentityFieldChangesTheFingerprint() {
        AuditResourceFingerprint base = AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001"));

        assertThat(AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.SEGMENT, "attr-1", "tenant-001", "program-001")))
                .as("different kind").isNotEqualTo(base);
        assertThat(AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-2", "tenant-001", "program-001")))
                .as("different resource id").isNotEqualTo(base);
        assertThat(AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-002", "program-001")))
                .as("different owner tenant").isNotEqualTo(base);
        assertThat(AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-002")))
                .as("different owner program").isNotEqualTo(base);
    }

    /**
     * Requirement 5: a naive separator join collides for ambiguous inputs, while the domain-separated,
     * length-prefixed tuple does not — checked at the pre-hash encoding level.
     */
    @Test
    void domainSeparatedTuplePreventsAmbiguousPreHashCollisions() throws Exception {
        OwnedResourceRef ambiguousA = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "a:b", "c", "d");
        OwnedResourceRef ambiguousB = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "a", "b:c", "d");

        // The naive encoding a string-join implementation would produce collides for these two identities.
        assertThat(naiveJoin(ambiguousA)).isEqualTo(naiveJoin(ambiguousB));
        // The real pre-hash encoding and the fingerprints do not.
        assertThat(AuditResourceFingerprint.canonicalPreimage(ResourceKind.ATTRIBUTE, "a:b", "c", "d"))
                .isNotEqualTo(AuditResourceFingerprint.canonicalPreimage(ResourceKind.ATTRIBUTE, "a", "b:c", "d"));
        assertThat(AuditResourceFingerprint.of(ambiguousA)).isNotEqualTo(AuditResourceFingerprint.of(ambiguousB));

        // Domain separation: the preimage starts with the length-prefixed domain tag, so a fingerprint hash
        // can never equal a hash computed for another purpose over the same identifiers.
        byte[] preimage = AuditResourceFingerprint.canonicalPreimage(
                ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(preimage));
        byte[] domain = in.readNBytes(in.readInt());
        assertThat(new String(domain, StandardCharsets.UTF_8)).isEqualTo(AuditResourceFingerprint.PREIMAGE_DOMAIN);
        byte[] kind = in.readNBytes(in.readInt());
        assertThat(new String(kind, StandardCharsets.UTF_8)).isEqualTo("ATTRIBUTE");
        // The stored hash is exactly the digest of that domain-separated preimage.
        assertThat(AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001")).hash())
                .isEqualTo(ContentHash.sha256(preimage));
    }

    /** Requirement 6: no public audit API accepts a free-text resource or payload reference. */
    @Test
    void noPublicAuditApiAcceptsFreeTextResourceOrPayloadReference() {
        for (RecordComponent component : SecurityAuditEvent.class.getRecordComponents()) {
            assertThat(component.getName())
                    .as("record component %s must not be a readable resource or payload reference", component.getName())
                    .doesNotContain("resourceReference", "payloadReference", "resourceKind", "memberData");
        }
        assertThat(Arrays.stream(SecurityAuditEvent.class.getRecordComponents())
                .filter(component -> component.getName().contains("resource"))
                .map(RecordComponent::getType))
                .containsExactly(AuditResourceFingerprint.class);

        for (Method method : SecurityAuditEvent.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            assertThat(method.getParameterTypes())
                    .as("public factory %s must not accept free text", method.getName())
                    .doesNotContain(String.class);
        }

        // The obsolete readable reference type is deleted, not deprecated.
        assertThatThrownBy(() -> Class.forName("com.company.campaign.security.audit.ResourceReference"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /** Requirement 7: NOT_FOUND rejects a fingerprint by direct construction and by deserialization. */
    @Test
    void notFoundRejectsAFingerprintOnConstructionAndDeserialization() {
        OwnedResourceRef resource = new OwnedResourceRef(
                ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");
        AuditResourceFingerprint fingerprint = AuditResourceFingerprint.of(resource);

        ContractAssertions.assertFailsWithCode(
                () -> new SecurityAuditEvent(
                        SecurityAction.RESOURCE_AUTHORIZATION,
                        SecurityDisposition.NOT_FOUND,
                        "CROSS_TENANT_RESOURCE",
                        OCCURRED_AT,
                        null,
                        "tenant-001", "program-001", "user-001", "scope-008",
                        fingerprint,
                        null),
                ContractErrorCodes.AUDIT_REFERENCE_INVALID);

        String json = """
                {"action":"RESOURCE_AUTHORIZATION","disposition":"NOT_FOUND",
                 "reasonCode":"CROSS_TENANT_RESOURCE","occurredAt":"2026-10-01T02:00:01Z",
                 "tenantId":"tenant-001","programId":"program-001","userId":"user-001",
                 "dataScopeId":"scope-008",
                 "resourceFingerprint":{"kind":"ATTRIBUTE","hash":"%s"}}
                """.formatted(fingerprint.hash().value());
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(json, SecurityAuditEvent.class),
                ContractErrorCodes.AUDIT_REFERENCE_INVALID);
    }

    /** Requirement 8: a fingerprint kind must equal the authorization decision resource kind. */
    @Test
    void fingerprintKindMustMatchTheAuthorizationDecision() {
        TrustedExecutionContext context = context();
        OwnedResourceRef attribute = new OwnedResourceRef(
                ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");
        AuthorizationDecision allowed = new FailClosedAuthorizationService(query -> DataScopeDecision.allowed())
                .authorize(AuthorizationRequest.of(context, attribute));

        AuditResourceFingerprint wrongKind = AuditResourceFingerprint.of(
                new OwnedResourceRef(ResourceKind.SEGMENT, "segment-1", "tenant-001", "program-001"));
        ContractAssertions.assertFailsWithCode(
                () -> SecurityAuditEvent.authorization(context, allowed, OCCURRED_AT, 0L, wrongKind, null),
                ContractErrorCodes.AUDIT_REFERENCE_INVALID);

        AuditResourceFingerprint matching = AuditResourceFingerprint.of(attribute);
        assertThat(SecurityAuditEvent.authorization(context, allowed, OCCURRED_AT, 0L, matching, null)
                .resourceFingerprint())
                .isEqualTo(matching);
    }

    /** Requirement 9: invalid reason code, trusted identifier and duration fail without echoing text. */
    @Test
    void invalidReasonCodeTrustedIdentifierAndDurationFailWithoutEchoingText() {
        for (String rejected : List.of("reason code", "lowercase", "MEMBER-42", "A".repeat(65), "BEARER SECRET")) {
            assertFailsSafely(() -> construct(rejected, "tenant-001", 0L), rejected);
        }
        for (String rejected : List.of("tenant 999", "{\"tenant\":\"x\"}", "+8613800000000", "Bearer abc123")) {
            assertFailsSafely(() -> construct("AUTHORIZATION_ALLOWED", rejected, 0L), rejected);
        }
        for (Long rejected : List.of(-1L, -999L)) {
            assertFailsSafely(() -> construct("AUTHORIZATION_ALLOWED", "tenant-001", rejected), String.valueOf(rejected));
        }

        // Deserialization keeps the same stable code for an invalid reason code.
        String json = """
                {"action":"RESOURCE_AUTHORIZATION","disposition":"ALLOWED","reasonCode":"bad reasonCode",
                 "occurredAt":"2026-10-01T02:00:01Z","tenantId":"tenant-001","programId":"program-001",
                 "userId":"user-001","dataScopeId":"scope-008"}
                """;
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(json, SecurityAuditEvent.class),
                ContractErrorCodes.AUDIT_FIELD_INVALID);
    }

    /** Requirement 8: the authorization factory always omits the fingerprint for a NOT_FOUND decision. */
    @Test
    void authorizationFactoryAlwaysOmitsFingerprintForNotFound() {
        TrustedExecutionContext context = context();
        OwnedResourceRef foreign = new OwnedResourceRef(
                ResourceKind.ATTRIBUTE, "member-42", "tenant-999", "program-999");
        AuthorizationDecision notFound = new FailClosedAuthorizationService(query -> DataScopeDecision.allowed())
                .authorize(AuthorizationRequest.of(context, foreign));

        SecurityAuditEvent event = SecurityAuditEvent.authorization(
                context, notFound, OCCURRED_AT, null, AuditResourceFingerprint.of(foreign), null);

        assertThat(event.disposition()).isEqualTo(SecurityDisposition.NOT_FOUND);
        assertThat(event.resourceFingerprint()).isNull();
        assertThat(canonicalJson.writeString(event))
                .doesNotContain("ATTRIBUTE", "tenant-999", "program-999", "member-42");
    }

    private SecurityAuditEvent construct(String reasonCode, String tenantId, Long durationNanos) {
        return new SecurityAuditEvent(
                SecurityAction.RESOURCE_AUTHORIZATION,
                SecurityDisposition.ALLOWED,
                reasonCode,
                OCCURRED_AT,
                durationNanos,
                tenantId, "program-001", "user-001", "scope-008",
                AuditResourceFingerprint.of(
                        new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001")),
                null);
    }

    private static void assertFailsSafely(Runnable action, String rejectedText) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).isInstanceOf(AuditFieldException.class);
        AuditFieldException failure = (AuditFieldException) thrown;
        assertThat(failure.code()).isEqualTo(ContractErrorCodes.AUDIT_FIELD_INVALID);
        // The rejected text may itself be a credential or foreign identifier, so it must never be echoed.
        assertThat(failure.getMessage()).doesNotContain(rejectedText);
        assertThat(failure.details().toString()).doesNotContain(rejectedText);
    }

    private static String naiveJoin(OwnedResourceRef resource) {
        return resource.kind().name() + ":" + resource.resourceId() + ":"
                + resource.tenantId() + ":" + resource.programId();
    }

    private static TrustedExecutionContext context() {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity("user-001", "https://issuer.example", AUTHENTICATED_AT),
                new PrincipalMembership(
                        "membership-1", "user-001", "tenant-001", "program-001", "scope-008",
                        MembershipStatus.ACTIVE, CampaignPermissions.minimum()),
                new TrustedExecutionContextRef(
                        "tenant-001", "program-001", "user-001", "scope-008", "metadata-17",
                        LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                        ZoneId.of("Asia/Shanghai"),
                        "CNY",
                        AS_OF,
                        WATERMARK));
    }
}