package com.company.campaign.security.audit;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationReason;
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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Safe audit metadata: no raw payload fields, no readable resource identifiers and no foreign scope. */
class AuditContractTest {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T02:00:01Z");

    private final CanonicalJson canonicalJson = new CanonicalJson();

    // Requirement 15: audit events carry a fingerprint/hash and cannot carry raw payloads or readable ids.
    @Test
    void auditEventCarriesSafeReferencesAndNoRawPayloadComponents() {
        TrustedExecutionContext context = context();
        OwnedResourceRef resource = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-001");
        AuthorizationDecision allowed = new FailClosedAuthorizationService(query -> DataScopeDecision.allowed())
                .authorize(AuthorizationRequest.of(context, resource));
        AuditResourceFingerprint fingerprint = AuditResourceFingerprint.of(resource);
        ContentHash redactedProjectionHash = ContentHash.sha256("redacted-payload".getBytes(StandardCharsets.UTF_8));

        SecurityAuditEvent event = SecurityAuditEvent.authorization(
                context, allowed, OCCURRED_AT, 1_500_000L, fingerprint, redactedProjectionHash);

        assertThat(event.disposition()).isEqualTo(SecurityDisposition.ALLOWED);
        assertThat(event.tenantId()).isEqualTo("tenant-001");
        assertThat(event.programId()).isEqualTo("program-001");
        assertThat(event.userId()).isEqualTo("user-001");
        assertThat(event.resourceFingerprint()).isEqualTo(fingerprint);
        assertThat(event.redactedProjectionHash()).isEqualTo(redactedProjectionHash);

        String recordComponents = Arrays.stream(SecurityAuditEvent.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.joining(","));
        assertThat(recordComponents).doesNotContain("payload", "sample", "request", "response", "memberData");
        assertThat(recordComponents).doesNotContain("resourceReference", "resourceKind");

        String rendered = canonicalJson.writeString(event);
        assertThat(rendered).contains("sha256:");
        assertThat(rendered).contains("\"resourceFingerprint\":{\"hash\":\"sha256:");
        assertThat(rendered).contains("\"kind\":\"ATTRIBUTE\"");
        assertThat(rendered).doesNotContain("attr-1", "raw-payload", "member-42", "Bearer", "password");
    }

    // Requirement 15: cross-tenant denial records the caller but not the foreign tenant or resource.
    @Test
    void crossTenantDenialDoesNotRecordForeignTenant() {
        TrustedExecutionContext context = context();
        OwnedResourceRef foreign = new OwnedResourceRef(ResourceKind.ATTRIBUTE, "member-42", "tenant-999", "program-001");
        AuthorizationDecision crossTenant = new FailClosedAuthorizationService(query -> DataScopeDecision.allowed())
                .authorize(AuthorizationRequest.of(context, foreign));

        SecurityAuditEvent event = SecurityAuditEvent.authorization(
                context, crossTenant, OCCURRED_AT, null, AuditResourceFingerprint.of(foreign), null);

        assertThat(event.disposition()).isEqualTo(SecurityDisposition.NOT_FOUND);
        assertThat(event.reasonCode()).isEqualTo(AuthorizationReason.CROSS_TENANT_RESOURCE.wireName());
        assertThat(event.tenantId()).isEqualTo("tenant-001");
        assertThat(event.resourceFingerprint()).isNull();

        String rendered = canonicalJson.writeString(event);
        assertThat(rendered).doesNotContain("tenant-999", "member-42", "ATTRIBUTE");
    }

    @Test
    void inMemorySinkRecordsEventsForDeterministicTests() {
        TrustedExecutionContext context = context();
        OwnedResourceRef resource = new OwnedResourceRef(ResourceKind.SEGMENT, "segment-1", "tenant-001", "program-001");
        AuthorizationDecision allowed = new FailClosedAuthorizationService(query -> DataScopeDecision.allowed())
                .authorize(AuthorizationRequest.of(context, resource));
        SecurityAuditEvent event = SecurityAuditEvent.authorization(
                context, allowed, OCCURRED_AT, null, AuditResourceFingerprint.of(resource), null);

        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.record(event);

        assertThat(sink.size()).isEqualTo(1);
        assertThat(sink.events()).containsExactly(event);
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