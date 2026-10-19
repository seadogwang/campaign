package com.company.campaign.lifecycle;

import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17-R1 requirement 7: an internal immutable receipt stays server side.
 *
 * <p>A receipt legitimately covers the caller's trusted ownership in its own checksum, but that is no reason
 * to re-expose it: the outward {@link ApplyResult}, {@link PreparePublishResult}, {@link PublishResult} and
 * {@link ForkResult} must carry only the identifiers and hashes a user interface needs to address what it
 * just saved or published. These tests lock that boundary by reflection and by serialization, and pair it
 * with a check that the internal checksum coverage was not weakened to achieve it.
 */
class LifecycleProjectionBoundaryTest {

    /** The server-side artifacts the outward results must never carry or re-expose. */
    private static final Set<Class<?>> INTERNAL_ARTIFACTS = Set.of(
            ApplyReceipt.class,
            PublishReceipt.class,
            PublishPreparation.class,
            PublishedCampaignDefinition.class,
            SavedCampaignDraft.class,
            LifecycleAuditEvent.class);

    private static final List<String> FORBIDDEN_NAME_FRAGMENTS =
            List.of("tenant", "program", "user", "scope", "permission", "membership", "principal");

    @Test
    void everyOutwardLifecycleResultExposesNoTrustedOwnership() {
        List<String> violations = new ArrayList<>();
        for (Class<?> result : List.of(
                ApplyResult.class, PreparePublishResult.class, PublishResult.class, ForkResult.class)) {
            for (RecordComponent component : result.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_NAME_FRAGMENTS) {
                    if (name.contains(forbidden)) {
                        violations.add(result.getSimpleName() + '.' + component.getName());
                    }
                }
                if (INTERNAL_ARTIFACTS.contains(component.getType())) {
                    violations.add(result.getSimpleName() + '.' + component.getName()
                            + " exposes " + component.getType().getSimpleName());
                }
            }
        }
        assertThat(violations)
                .as("an outward lifecycle result may not re-expose a trusted identifier or an internal receipt")
                .isEmpty();
    }

    @Test
    void serializingAnOutwardResultLeaksNoTrustedValueAndNoManifestPayload() {
        CanonicalJson canonicalJson = new CanonicalJson();

        var apply = PublishScenario.active();
        ApplyResult applyResult = apply.applyService().apply(
                new ApplyCommand(apply.plan.planId(), apply.plan.planHash()), apply.context);
        PreparePublishResult prepared = apply.prepare();
        PublishResult published = apply.confirm(prepared, "key-1", List.of());
        ForkResult forked = apply.forkService().fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedRevision()),
                apply.context);

        for (Object result : List.of(applyResult, prepared, published, forked)) {
            String serialized = new String(canonicalJson.write(result), StandardCharsets.UTF_8);
            assertThat(serialized)
                    .as("%s must not serialize a trusted owner", result.getClass().getSimpleName())
                    .doesNotContain(apply.context.tenantId())
                    .doesNotContain(apply.context.programId())
                    .doesNotContain(apply.context.userId())
                    .doesNotContain(apply.context.dataScopeId());
            // No raw semantic payload escapes either: the Draft and published bytes stay server side.
            assertThat(serialized).doesNotContain("label").doesNotContain("welcome");
        }
    }

    @Test
    void theInternalReceiptsStillCarryAndCoverTheTrustedOwnership() {
        // Keeping receipts server side must not have weakened what they prove: two receipts that differ only
        // in the trusted tenant are still distinct, so the owner really is inside the checksum material.
        ApplyReceipt base = receipt("tenant-001");
        ApplyReceipt otherTenant = receipt("tenant-999");
        ApplyReceipt otherUser = receiptForUser("user-002");

        assertThat(otherTenant.receiptHash()).isNotEqualTo(base.receiptHash());
        assertThat(otherUser.receiptHash()).isNotEqualTo(base.receiptHash());
        assertThat(base.toString())
                .as("a receipt never prints its owner even internally")
                .doesNotContain("tenant-001")
                .doesNotContain("user-001");
    }

    @Test
    void theApplyResultStillAddressesExactlyWhatTheApplySaved() {
        var scenario = PublishScenario.active();
        ApplyResult result = scenario.applyService().apply(
                new ApplyCommand(scenario.plan.planId(), scenario.plan.planHash()), scenario.context);

        SavedCampaignDraft draft = scenario.store.findDraft(result.campaignId()).orElseThrow();
        assertThat(result.draftId()).isEqualTo(draft.draftId());
        assertThat(result.draftRevision()).isEqualTo(draft.draftRevision());
        assertThat(result.campaignRevision()).isEqualTo(draft.campaignRevision());
        assertThat(result.draftHash()).isEqualTo(draft.draftHash());
        assertThat(result.manifestHash()).isEqualTo(draft.manifestHash());
        assertThat(result.draftIdentity()).isEqualTo(draft.draftIdentity());
    }

    private static ApplyReceipt receipt(String tenantId) {
        return ApplyReceipt.of(
                "apply-receipt-000000000001", tenantId, "program-001", "user-001",
                "plan-000000000001", otherHash("plan"), "campaign-000000000001", 1L,
                "draft-000000000001", 1L, otherHash("manifest"), otherHash("content"),
                LifecycleTestFixtures.APPLIED_AT);
    }

    private static ApplyReceipt receiptForUser(String userId) {
        return ApplyReceipt.of(
                "apply-receipt-000000000001", "tenant-001", "program-001", userId,
                "plan-000000000001", otherHash("plan"), "campaign-000000000001", 1L,
                "draft-000000000001", 1L, otherHash("manifest"), otherHash("content"),
                LifecycleTestFixtures.APPLIED_AT);
    }

    private static ContentHash otherHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}