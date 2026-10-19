package com.company.campaign.security.context;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trusted context resolution: closed permission set, fail-closed resolution and forged-payload resistance.
 */
class TrustedContextTest {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    // Requirement 1: the permission wire names are exactly the seven minimum permissions.
    @Test
    void permissionWireNamesAreExactlyTheSevenMinimumNames() {
        assertThat(Arrays.stream(CampaignPermission.values()).map(CampaignPermission::wireName).toList())
                .containsExactly(
                        "campaign.read",
                        "campaign.edit",
                        "audience.preview",
                        "campaign.publish",
                        "campaign.operate",
                        "metadata.manage",
                        "segment.evaluate");
        assertThat(CampaignPermissions.minimum())
                .containsExactlyInAnyOrder(CampaignPermission.values());
        assertThat(CampaignPermission.fromWireName("audience.preview"))
                .isEqualTo(CampaignPermission.AUDIENCE_PREVIEW);
        ContractAssertions.assertFailsWithCode(
                () -> CampaignPermission.fromWireName("audience.sample"),
                ContractErrorCodes.PERMISSION_UNKNOWN);
    }

    // Requirement 1: permission collections are immutable.
    @Test
    void permissionCollectionsAreImmutable() {
        assertThatThrownBy(() -> CampaignPermissions.minimum().add(CampaignPermission.CAMPAIGN_READ))
                .isInstanceOf(UnsupportedOperationException.class);

        PrincipalMembership membership = membership(
                "user-001", "tenant-001", "program-001", "scope-008",
                MembershipStatus.ACTIVE, CampaignPermissions.minimum());
        assertThatThrownBy(() -> membership.permissions().remove(CampaignPermission.CAMPAIGN_READ))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Requirement 2: identity/membership mismatch and inactive membership fail closed with stable codes.
    @Test
    void identityMembershipMismatchFailsClosed() {
        ContractAssertions.assertFailsWithCode(
                () -> TrustedContextResolver.resolve(
                        identity("user-other"),
                        membership("user-001", "tenant-001", "program-001", "scope-008",
                                MembershipStatus.ACTIVE, CampaignPermissions.minimum()),
                        reference("tenant-001", "program-001", "user-001", "scope-008", "metadata-17")),
                ContractErrorCodes.TRUSTED_IDENTITY_MISMATCH);
    }

    @Test
    void inactiveMembershipFailsClosed() {
        ContractAssertions.assertFailsWithCode(
                () -> TrustedContextResolver.resolve(
                        identity("user-001"),
                        membership("user-001", "tenant-001", "program-001", "scope-008",
                                MembershipStatus.INACTIVE, CampaignPermissions.minimum()),
                        reference("tenant-001", "program-001", "user-001", "scope-008", "metadata-17")),
                ContractErrorCodes.MEMBERSHIP_INACTIVE);
    }

    @Test
    void tenantProgramUserAndScopeMismatchFailClosed() {
        PrincipalMembership membership = membership(
                "user-001", "tenant-001", "program-001", "scope-008",
                MembershipStatus.ACTIVE, CampaignPermissions.minimum());

        for (TrustedExecutionContextRef mismatched : List.of(
                reference("tenant-002", "program-001", "user-001", "scope-008", "metadata-17"),
                reference("tenant-001", "program-002", "user-001", "scope-008", "metadata-17"),
                reference("tenant-001", "program-001", "user-002", "scope-008", "metadata-17"),
                reference("tenant-001", "program-001", "user-001", "scope-009", "metadata-17"))) {
            ContractAssertions.assertFailsWithCode(
                    () -> TrustedContextResolver.resolve(identity("user-001"), membership, mismatched),
                    ContractErrorCodes.TRUSTED_CONTEXT_MISMATCH);
        }
    }

    @Test
    void invalidSnapshotContextFailsClosed() {
        PrincipalMembership membership = membership(
                "user-001", "tenant-001", "program-001", "scope-008",
                MembershipStatus.ACTIVE, CampaignPermissions.minimum());

        ContractAssertions.assertFailsWithCode(
                () -> TrustedContextResolver.resolve(identity("user-001"), membership, null),
                ContractErrorCodes.TRUSTED_CONTEXT_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> TrustedContextResolver.resolve(
                        identity("user-001"),
                        membership,
                        reference("tenant-001", "program-001", "user-001", "scope-008", " ")),
                ContractErrorCodes.TRUSTED_CONTEXT_INVALID);
    }

    // Requirement 3: the context is built from host values, through the provider port or resolver.
    @Test
    void trustedContextIsBuiltFromVerifiedHostValues() {
        TrustedExecutionContext context = validContext();

        assertThat(context.userId()).isEqualTo("user-001");
        assertThat(context.tenantId()).isEqualTo("tenant-001");
        assertThat(context.programId()).isEqualTo("program-001");
        assertThat(context.dataScopeId()).isEqualTo("scope-008");
        assertThat(context.permissions()).containsExactlyInAnyOrder(CampaignPermission.values());
        assertThat(context.snapshotRef().metadataSnapshotId()).isEqualTo("metadata-17");

        TrustedContextProvider provider = new TrustedContextProvider() {
            @Override
            public VerifiedIdentity verifiedIdentity() {
                return identity("user-001");
            }

            @Override
            public PrincipalMembership verifiedMembership() {
                return membership("user-001", "tenant-001", "program-001", "scope-008",
                        MembershipStatus.ACTIVE, CampaignPermissions.minimum());
            }

            @Override
            public TrustedExecutionContextRef executionReference() {
                return reference("tenant-001", "program-001", "user-001", "scope-008", "metadata-17");
            }
        };

        assertThat(provider.trustedContext()).isEqualTo(context);
    }

    // Requirement 3: forged Tool payload fields cannot supply trusted identity.
    @Test
    void forgedToolPayloadCannotSupplyTrustedIdentity() {
        for (String reserved : List.of(
                "tenantId", "programId", "userId", "dataScopeId", "permissions", "role", "dataScope")) {
            ContractAssertions.assertFailsWithCode(
                    () -> ToolInput.of(payload(reserved, "forged")),
                    ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
        }

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("nested", Map.of("tenantId", "forged"));
        ContractAssertions.assertFailsWithCode(
                () -> ToolInput.of(nested),
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD);
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private static VerifiedIdentity identity(String subjectId) {
        return new VerifiedIdentity(subjectId, "https://issuer.example", AUTHENTICATED_AT);
    }

    private static PrincipalMembership membership(
            String userId,
            String tenantId,
            String programId,
            String dataScopeId,
            MembershipStatus status,
            Set<CampaignPermission> permissions) {
        return new PrincipalMembership(
                "membership-1", userId, tenantId, programId, dataScopeId, status, permissions);
    }

    private static TrustedExecutionContextRef reference(
            String tenantId, String programId, String userId, String dataScopeId, String metadataSnapshotId) {
        return new TrustedExecutionContextRef(
                tenantId,
                programId,
                userId,
                dataScopeId,
                metadataSnapshotId,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                ZoneId.of("Asia/Shanghai"),
                "CNY",
                AS_OF,
                WATERMARK);
    }

    private static TrustedExecutionContext validContext() {
        return TrustedContextResolver.resolve(
                identity("user-001"),
                membership("user-001", "tenant-001", "program-001", "scope-008",
                        MembershipStatus.ACTIVE, CampaignPermissions.minimum()),
                reference("tenant-001", "program-001", "user-001", "scope-008", "metadata-17"));
    }
}