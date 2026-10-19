package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1: exact Skill/Tool identifiers, versions, order and catalog hash stability.
 */
class CampaignToolVocabularyTest {

    @Test
    void theClosedSkillVocabularyIsStableAndVersioned() {
        assertThat(CampaignSkillIds.ordered()).containsExactly(
                "segment.rule-based", "message.template", "promotion.reference",
                "journey.flow", "schedule.basic", "governance.limit");
        for (String skillId : CampaignSkillIds.ordered()) {
            assertThat(CampaignSkillIds.isKnown(skillId)).isTrue();
            assertThat(CampaignSkillIds.versionOf(skillId)).isEqualTo("1.0");
            assertThat(CampaignSkillIds.capabilitiesOf(skillId)).isNotEmpty();
        }
        // Five Skills own exactly one capability each; the multi-component Journey Skill owns exactly the six
        // accepted Flow node capabilities and therefore answers no single capability of its own name.
        assertThat(CampaignSkillIds.capabilityOf(CampaignSkillIds.MESSAGE_TEMPLATE))
                .isEqualTo("message.template");
        assertThat(CampaignSkillIds.capabilityOf(CampaignSkillIds.JOURNEY_FLOW)).isNull();
        assertThat(CampaignSkillIds.capabilitiesOf(CampaignSkillIds.JOURNEY_FLOW)).containsExactly(
                "flow.start", "flow.message", "flow.wait", "flow.condition", "flow.reward", "flow.end");
        assertThat(CampaignSkillIds.ownsCapability(CampaignSkillIds.JOURNEY_FLOW, "flow.message")).isTrue();
        assertThat(CampaignSkillIds.ownsCapability(CampaignSkillIds.JOURNEY_FLOW, "journey.flow")).isFalse();
        assertThat(CampaignSkillIds.implementedSkills()).containsExactly(
                "segment.rule-based", "message.template", "promotion.reference",
                "journey.flow", "schedule.basic", "governance.limit");
        assertThat(CampaignSkillIds.isImplemented("journey.flow")).isTrue();
        assertThat(CampaignSkillIds.isKnown("segment.rule-based.renamed")).isFalse();
    }

    @Test
    void implementedAndPlannedToolVocabularyIsExactAndOrdered() {
        assertThat(CampaignToolIds.implemented()).containsExactly(
                "message.template.discover", "message.template.resolve", "message.variables.validate",
                "message.preview.safe", "message.inline.preview.safe", "reward.discover", "reward.resolve",
                "reward.exposure.calculate",
                "reward.preview.safe", "journey.node.catalog", "journey.compose", "journey.validate",
                "journey.simulate", "schedule.normalize", "schedule.validate",
                "governance.exposure.calculate", "governance.validate");
        assertThat(CampaignToolIds.planned()).containsExactly("schedule.occurrences.preview");
        assertThat(CampaignToolIds.isImplemented("message.template.discover")).isTrue();
        assertThat(CampaignToolIds.isImplemented("schedule.validate")).isTrue();
        assertThat(CampaignToolIds.isImplemented("governance.validate")).isTrue();
        assertThat(CampaignToolIds.isImplemented("journey.node.catalog")).isTrue();
        assertThat(CampaignToolIds.isImplemented("journey.validate")).isTrue();
        assertThat(CampaignToolIds.isPlanned("journey.node.catalog")).isFalse();
        assertThat(CampaignToolIds.isPlanned("schedule.occurrences.preview")).isTrue();
        assertThat(CampaignToolIds.isKnown("journey.node.catalog")).isTrue();
        assertThat(CampaignToolIds.isKnown("message.template.rename")).isFalse();
    }

    @Test
    void everyImplementedDescriptorDeclaresItsOwnerVersionRankPurposeAndBounds() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        for (String toolId : CampaignToolIds.implemented()) {
            CampaignToolDescriptor descriptor = catalog.descriptor(toolId);
            assertThat(descriptor).as(toolId).isNotNull();
            assertThat(descriptor.toolVersion()).as(toolId).isEqualTo("1.0");
            assertThat(descriptor.modelCallable()).as(toolId).isTrue();
            assertThat(descriptor.available()).as(toolId).isTrue();
            assertThat(descriptor.purposeCode()).as(toolId).matches("[A-Z][A-Z0-9_]{0,63}");
        }
        assertThat(catalog.ownedBy(CampaignSkillIds.MESSAGE_TEMPLATE))
                .extracting(CampaignToolDescriptor::toolId)
                .containsExactly("message.template.discover", "message.template.resolve",
                        "message.variables.validate", "message.preview.safe", "message.inline.preview.safe");
        assertThat(catalog.ownedBy(CampaignSkillIds.PROMOTION_REFERENCE))
                .extracting(CampaignToolDescriptor::rank).containsExactly(0, 1, 2, 3);
    }

    @Test
    void plannedDescriptorsAreExplicitlyUnavailableAndNeverModelCallable() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        for (String toolId : CampaignToolIds.planned()) {
            CampaignToolDescriptor descriptor = catalog.descriptor(toolId);
            assertThat(descriptor.available()).as(toolId).isFalse();
            assertThat(descriptor.modelCallable()).as(toolId).isFalse();
        }
        CampaignToolDescriptor bridge =
                catalog.descriptor(CampaignToolIds.SEGMENT_ARTIFACT_REFERENCE);
        assertThat(bridge.available()).isTrue();
        assertThat(bridge.modelCallable()).isFalse();
    }

    @Test
    void theIdentifierListsAndTheSkillImplementationFactsAgreeExactlyWithTheDescriptors() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();

        // The closed vocabulary and the descriptor set are the same fact, in the same canonical order.
        assertThat(catalog.descriptors())
                .extracting(CampaignToolDescriptor::toolId)
                .containsExactlyElementsOf(CampaignToolIds.ordered());
        assertThat(CampaignToolIds.implemented()).containsExactlyElementsOf(
                catalog.descriptors().stream()
                        .filter(CampaignToolDescriptor::available)
                        .filter(CampaignToolDescriptor::modelCallable)
                        .map(CampaignToolDescriptor::toolId)
                        .toList());
        assertThat(CampaignToolIds.planned()).containsExactlyElementsOf(
                catalog.descriptors().stream()
                        .filter(descriptor -> !descriptor.available())
                        .map(CampaignToolDescriptor::toolId)
                        .toList());

        // Every implemented Skill owns at least one implemented descriptor and every descriptor names a
        // server-owned Skill; the Journey Skill owns its four topology-aware Tools in declared rank order.
        for (String skillId : CampaignSkillIds.implementedSkills()) {
            assertThat(catalog.ownedBy(skillId)).as(skillId).isNotEmpty();
        }
        assertThat(CampaignSkillIds.implementedSkills())
                .contains(CampaignSkillIds.JOURNEY_FLOW);
        assertThat(catalog.ownedBy(CampaignSkillIds.JOURNEY_FLOW))
                .extracting(CampaignToolDescriptor::toolId)
                .containsExactly("journey.node.catalog", "journey.compose", "journey.validate",
                        "journey.simulate");
        assertThat(catalog.ownedBy(CampaignSkillIds.JOURNEY_FLOW))
                .extracting(CampaignToolDescriptor::rank).containsExactly(0, 1, 2, 3);
        for (CampaignToolDescriptor descriptor : catalog.descriptors()) {
            assertThat(CampaignSkillIds.isKnown(descriptor.owningSkillId()))
                    .as(descriptor.toolId()).isTrue();
            // An implemented Tool can only belong to an implemented Skill. A planned Tool may still be
            // reserved by one: {@code schedule.occurrences.preview} belongs to the implemented
            // {@code schedule.basic} Skill but stays unavailable until T21's publication-time inputs exist.
            if (descriptor.available() && descriptor.modelCallable()) {
                assertThat(CampaignSkillIds.isImplemented(descriptor.owningSkillId()))
                        .as(descriptor.toolId()).isTrue();
            }
        }
        for (String toolId : CampaignToolIds.ordered()) {
            assertThat(catalog.declares(toolId)).as(toolId).isTrue();
        }
    }

    @Test
    void theCatalogHashIsStableAndCoversEverySemanticField() {
        CampaignToolCatalog first = CampaignToolCatalog.standard();
        CampaignToolCatalog second = CampaignToolCatalog.standard();
        assertThat(first.catalogHash()).isEqualTo(second.catalogHash());
        assertThat(first.catalogHash()).isEqualTo(first.recomputeCatalogHash());
        assertThat(first.catalogHash()).isEqualTo(second.catalogHash());

        CampaignToolDescriptor base =
                new CampaignToolDescriptor("sample.tool", "1.0", CampaignSkillIds.MESSAGE_TEMPLATE, 0,
                        CampaignToolKind.DISCOVERY, "SAMPLE_DISCOVERY", 128, 128, true, true);
        ContentHash baseHash = base.descriptorHash();
        assertThat(base.descriptorHash()).isEqualTo(baseHash);
        assertThat(base.descriptorHash()).isNotEqualTo(new CampaignToolDescriptor(
                "sample.tool", "1.1", CampaignSkillIds.MESSAGE_TEMPLATE, 0,
                CampaignToolKind.DISCOVERY, "SAMPLE_DISCOVERY", 128, 128, true, true).descriptorHash());
        assertThat(base.descriptorHash()).isNotEqualTo(new CampaignToolDescriptor(
                "sample.tool", "1.0", CampaignSkillIds.PROMOTION_REFERENCE, 0,
                CampaignToolKind.DISCOVERY, "SAMPLE_DISCOVERY", 128, 128, true, true).descriptorHash());
        assertThat(base.descriptorHash()).isNotEqualTo(new CampaignToolDescriptor(
                "sample.tool", "1.0", CampaignSkillIds.MESSAGE_TEMPLATE, 1,
                CampaignToolKind.DISCOVERY, "SAMPLE_DISCOVERY", 128, 128, true, true).descriptorHash());
        assertThat(base.descriptorHash()).isNotEqualTo(new CampaignToolDescriptor(
                "sample.tool", "1.0", CampaignSkillIds.MESSAGE_TEMPLATE, 0,
                CampaignToolKind.PREVIEW, "SAMPLE_DISCOVERY", 128, 128, true, true).descriptorHash());
        assertThat(base.descriptorHash()).isNotEqualTo(new CampaignToolDescriptor(
                "sample.tool", "1.0", CampaignSkillIds.MESSAGE_TEMPLATE, 0,
                CampaignToolKind.DISCOVERY, "SAMPLE_DISCOVERY", 256, 128, true, true).descriptorHash());
    }

    @Test
    void theCatalogVersionIsBoundedAndExposed() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        assertThat(catalog.catalogVersion()).isEqualTo("campaign-tool-catalog-4.0");
        assertThat(catalog.descriptors()).isNotEmpty();
        assertThat(CampaignSkillIds.ordered()).isEqualTo(List.copyOf(CampaignSkillIds.ordered()));
    }
}