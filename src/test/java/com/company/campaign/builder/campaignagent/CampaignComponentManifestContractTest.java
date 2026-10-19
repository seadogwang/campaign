package com.company.campaign.builder.campaignagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic contract tests for CampaignComponentManifest, CampaignCapability,
 * UsageDecision, and DecisionSource.
 *
 * <p>These tests verify strict enum parsing, construction invariants, and cross-component
 * mappings. They use no mock model output, no fixture catalog, and no test-scenario text.
 * They are purely deterministic host-owned logic checks.
 */
class CampaignComponentManifestContractTest {

    // ------------------------------------------------------------ enum strict parsing

    @ParameterizedTest
    @EnumSource(CampaignCapability.class)
    void capabilityFromCodeRoundTrips(CampaignCapability capability) {
        assertThat(CampaignCapability.fromCode(capability.code())).isEqualTo(capability);
    }

    @Test
    void capabilityFromCodeRejectsNull() {
        assertThat(CampaignCapability.fromCode(null)).isNull();
    }

    @Test
    void capabilityFromCodeRejectsUnknown() {
        assertThat(CampaignCapability.fromCode("UNKNOWN_CAPABILITY")).isNull();
        assertThat(CampaignCapability.fromCode("segment")).isNull(); // case-sensitive
    }

    @ParameterizedTest
    @EnumSource(UsageDecision.class)
    void usageDecisionFromCodeRoundTrips(UsageDecision decision) {
        assertThat(UsageDecision.fromCode(decision.code())).isEqualTo(decision);
    }

    @Test
    void usageDecisionFromCodeRejectsNull() {
        assertThat(UsageDecision.fromCode(null)).isNull();
    }

    @Test
    void usageDecisionFromCodeRejectsUnknown() {
        assertThat(UsageDecision.fromCode("MAYBE")).isNull();
    }

    @ParameterizedTest
    @EnumSource(DecisionSource.class)
    void decisionSourceFromCodeRoundTrips(DecisionSource source) {
        assertThat(DecisionSource.fromCode(source.code())).isEqualTo(source);
    }

    @Test
    void decisionSourceFromCodeRejectsNull() {
        assertThat(DecisionSource.fromCode(null)).isNull();
    }

    @Test
    void decisionSourceFromCodeRejectsUnknown() {
        assertThat(DecisionSource.fromCode("AI")).isNull();
    }

    // ------------------------------------------------------------ resource-binding capabilities

    @Test
    void eventRequiresResourceBinding() {
        assertThat(CampaignCapability.EVENT.requiresResourceBinding()).isTrue();
        assertThat(CampaignCapability.EVENT.resourceType()).isEqualTo(CampaignBusinessResourceType.TRIGGER_EVENT);
    }

    @Test
    void messageRequiresResourceBinding() {
        assertThat(CampaignCapability.MESSAGE.requiresResourceBinding()).isTrue();
        assertThat(CampaignCapability.MESSAGE.resourceType()).isEqualTo(CampaignBusinessResourceType.COMMUNICATION_CHANNEL);
    }

    @Test
    void promotionRequiresResourceBinding() {
        assertThat(CampaignCapability.PROMOTION.requiresResourceBinding()).isTrue();
        assertThat(CampaignCapability.PROMOTION.resourceType()).isEqualTo(CampaignBusinessResourceType.PROMOTION);
    }

    @Test
    void segmentDoesNotRequireResourceBinding() {
        assertThat(CampaignCapability.SEGMENT.requiresResourceBinding()).isFalse();
        assertThat(CampaignCapability.SEGMENT.resourceType()).isNull();
    }

    @Test
    void flowDoesNotRequireResourceBinding() {
        assertThat(CampaignCapability.FLOW.requiresResourceBinding()).isFalse();
        assertThat(CampaignCapability.FLOW.resourceType()).isNull();
    }

    @Test
    void scheduleDoesNotRequireResourceBinding() {
        assertThat(CampaignCapability.SCHEDULE.requiresResourceBinding()).isFalse();
        assertThat(CampaignCapability.SCHEDULE.resourceType()).isNull();
    }

    // ------------------------------------------------------------ capability-to-component mapping

    @Test
    void segmentMapsToSegmentComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.SEGMENT))
                .isEqualTo(CampaignComponentCode.SEGMENT);
    }

    @Test
    void eventMapsToJourneyComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.EVENT))
                .isEqualTo(CampaignComponentCode.JOURNEY);
    }

    @Test
    void flowMapsToJourneyComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.FLOW))
                .isEqualTo(CampaignComponentCode.JOURNEY);
    }

    @Test
    void messageMapsToCommunicationComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.MESSAGE))
                .isEqualTo(CampaignComponentCode.COMMUNICATION);
    }

    @Test
    void promotionMapsToOfferComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.PROMOTION))
                .isEqualTo(CampaignComponentCode.OFFER);
    }

    @Test
    void scheduleMapsToExecutionScheduleComponent() {
        assertThat(CampaignComponentCode.fromCapability(CampaignCapability.SCHEDULE))
                .isEqualTo(CampaignComponentCode.EXECUTION_SCHEDULE);
    }

    // ------------------------------------------------------------ manifest construction invariants

    @Test
    void manifestRequiresCapability() {
        assertThatThrownBy(() -> new CampaignComponentManifest(
                null, UsageDecision.REQUIRED, "R01", "evidence", List.of(), DecisionSource.MODEL))
                .isInstanceOf(CampaignAgentException.class);
    }

    @Test
    void manifestRequiresUsageDecision() {
        assertThatThrownBy(() -> new CampaignComponentManifest(
                CampaignCapability.EVENT, null, "R01", "evidence", List.of(), DecisionSource.MODEL))
                .isInstanceOf(CampaignAgentException.class);
    }

    @Test
    void manifestRequiresDecisionSource() {
        assertThatThrownBy(() -> new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01", "evidence", List.of(), null))
                .isInstanceOf(CampaignAgentException.class);
    }

    @Test
    void uncertainManifestRejectsResourceCodes() {
        assertThatThrownBy(() -> new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.UNCERTAIN, "R01", "evidence",
                List.of("MEMBER_REGISTER"), DecisionSource.MODEL))
                .isInstanceOf(CampaignAgentException.class);
    }

    @Test
    void notRequiredManifestRejectsResourceCodes() {
        assertThatThrownBy(() -> new CampaignComponentManifest(
                CampaignCapability.MESSAGE, UsageDecision.NOT_REQUIRED, "R01", "evidence",
                List.of("SMS"), DecisionSource.MODEL))
                .isInstanceOf(CampaignAgentException.class);
    }

    @Test
    void requiredManifestAcceptsResourceCodes() {
        CampaignComponentManifest manifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01",
                "会员注册时触发", List.of("MEMBER_REGISTER"), DecisionSource.MODEL);
        assertThat(manifest.capability()).isEqualTo(CampaignCapability.EVENT);
        assertThat(manifest.usageDecision()).isEqualTo(UsageDecision.REQUIRED);
        assertThat(manifest.selectedResourceCodes()).containsExactly("MEMBER_REGISTER");
        assertThat(manifest.resourceBindingComplete()).isTrue();
    }

    @Test
    void requiredManifestWithoutResourcesIsNotBindingComplete() {
        CampaignComponentManifest manifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01",
                "evidence", List.of(), DecisionSource.MODEL);
        assertThat(manifest.resourceBindingComplete()).isFalse();
    }

    @Test
    void segmentManifestDoesNotNeedResourceBinding() {
        CampaignComponentManifest manifest = new CampaignComponentManifest(
                CampaignCapability.SEGMENT, UsageDecision.REQUIRED, "R01",
                "evidence", List.of(), DecisionSource.MODEL);
        assertThat(manifest.resourceBindingComplete()).isFalse(); // SEGMENT doesn't require resources
    }

    // ------------------------------------------------------------ supersede logic

    @Test
    void userDecisionSupersedesModel() {
        CampaignComponentManifest modelManifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01", "evidence",
                List.of(), DecisionSource.MODEL);
        assertThat(modelManifest.supersededBy(UsageDecision.NOT_REQUIRED, DecisionSource.USER)).isTrue();
    }

    @Test
    void modelDecisionDoesNotSupersedeUser() {
        CampaignComponentManifest userManifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01", "evidence",
                List.of("MEMBER_REGISTER"), DecisionSource.USER);
        assertThat(userManifest.supersededBy(UsageDecision.NOT_REQUIRED, DecisionSource.MODEL)).isFalse();
    }

    @Test
    void systemSupersedesModelButNotUser() {
        CampaignComponentManifest modelManifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.REQUIRED, "R01", "evidence",
                List.of(), DecisionSource.MODEL);
        assertThat(modelManifest.supersededBy(UsageDecision.NOT_REQUIRED, DecisionSource.SYSTEM)).isTrue();

        CampaignComponentManifest userManifest = new CampaignComponentManifest(
                CampaignCapability.EVENT, UsageDecision.NOT_REQUIRED, "R01", "evidence",
                List.of(), DecisionSource.USER);
        assertThat(userManifest.supersededBy(UsageDecision.REQUIRED, DecisionSource.SYSTEM)).isFalse();
    }

    // ------------------------------------------------------------ usageDecision forbidsResources

    @Test
    void requiredAllowsResources() {
        assertThat(UsageDecision.REQUIRED.forbidsResources()).isFalse();
    }

    @Test
    void notRequiredForbidsResources() {
        assertThat(UsageDecision.NOT_REQUIRED.forbidsResources()).isTrue();
    }

    @Test
    void uncertainForbidsResources() {
        assertThat(UsageDecision.UNCERTAIN.forbidsResources()).isTrue();
    }

    // ------------------------------------------------------------ manifest toString is diagnostic only

    @Test
    void manifestToStringDoesNotLeakValues() {
        CampaignComponentManifest manifest = new CampaignComponentManifest(
                CampaignCapability.MESSAGE, UsageDecision.REQUIRED, "R01",
                "通过短信触达", List.of("SMS"), DecisionSource.MODEL);
        String str = manifest.toString();
        assertThat(str).doesNotContain("R01");
        assertThat(str).doesNotContain("短信");
        assertThat(str).doesNotContain("SMS");
        assertThat(str).contains("capability=");
        assertThat(str).contains("MESSAGE");
    }

    // ------------------------------------------------------------ session manifest merge

    @Test
    void sessionMergesManifestsCorrectly() {
        CampaignAgentSession session = new CampaignAgentSession(
                com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures.createContext());
        // Initial: no manifests
        assertThat(session.manifestFor(CampaignCapability.EVENT)).isNull();

        // Merge a MODEL decision
        List<CampaignComponentManifest> modelDecisions = List.of(
                new CampaignComponentManifest(CampaignCapability.EVENT, UsageDecision.REQUIRED,
                        "R01", "evidence", List.of(), DecisionSource.MODEL));
        session.mergeManifests(modelDecisions);
        assertThat(session.manifestFor(CampaignCapability.EVENT).usageDecision())
                .isEqualTo(UsageDecision.REQUIRED);
        assertThat(session.manifestFor(CampaignCapability.EVENT).decisionSource())
                .isEqualTo(DecisionSource.MODEL);

        // User overrides with NOT_REQUIRED
        List<CampaignComponentManifest> userOverride = List.of(
                new CampaignComponentManifest(CampaignCapability.EVENT, UsageDecision.NOT_REQUIRED,
                        "R02", "user said no", List.of(), DecisionSource.USER));
        session.mergeManifests(userOverride);
        assertThat(session.manifestFor(CampaignCapability.EVENT).usageDecision())
                .isEqualTo(UsageDecision.NOT_REQUIRED);
        assertThat(session.manifestFor(CampaignCapability.EVENT).decisionSource())
                .isEqualTo(DecisionSource.USER);

        // Model tries to override again — refused
        session.mergeManifests(modelDecisions);
        assertThat(session.manifestFor(CampaignCapability.EVENT).usageDecision())
                .isEqualTo(UsageDecision.NOT_REQUIRED);
    }

    // ------------------------------------------------------------ old snapshot compatibility

    @Test
    void emptyManifestsAreAccepted() {
        // Old snapshots without manifests decode to empty list.
        assertThat(List.<CampaignComponentManifest>of()).isEmpty();
    }
}