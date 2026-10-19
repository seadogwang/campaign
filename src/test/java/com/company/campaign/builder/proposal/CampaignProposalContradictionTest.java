package com.company.campaign.builder.proposal;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 1 regression: a {@code DELETE} and a {@code MOVE} of one identity is a contradiction in either
 * operation order, refused by the model itself and by the strict codec with one stable code and no leakage.
 */
class CampaignProposalContradictionTest {

    private static final ContentHash HASH = ContentHash.sha256("pin".getBytes(StandardCharsets.UTF_8));
    private static final String SENTINEL = "SENTINEL-PAYLOAD-DO-NOT-ECHO";
    private static final String CATALOG_HASH = "sha256:" + "a".repeat(64);
    private static final String POLICY_HASH = "sha256:" + "b".repeat(64);

    private final CampaignProposalCodec codec = new CampaignProposalCodec();

    // ------------------------------------------------------------------ direct model

    @Test
    void theModelRejectsDeleteThenMove() {
        assertContradiction(() -> patch(List.of(
                ProposalOperation.delete("component-a"),
                moveAfter("component-a", "component-b"))));
    }

    @Test
    void theModelRejectsMoveThenDeleteInTheOppositeOrdering() {
        assertContradiction(() -> patch(List.of(
                moveAfter("component-a", "component-b"),
                ProposalOperation.delete("component-a"))));
    }

    @Test
    void theModelStillAcceptsUpsertAndMoveOfOneIdentity() {
        assertThatNoException().isThrownBy(() -> patch(List.of(
                ProposalOperation.upsertExisting("message.template", "component-a",
                        CapabilityPayload.of(java.util.Map.of("text", "hi"))),
                moveAfter("component-a", "component-b"))));
    }

    // ------------------------------------------------------------------ strict codec

    @Test
    void theCodecRejectsDeleteThenMove() {
        assertCodecContradiction("""
                {"op":"DELETE","logicalId":"component-a"},
                {"op":"MOVE","logicalId":"component-a","after":"component-b"}""");
    }

    @Test
    void theCodecRejectsMoveThenDeleteInTheOppositeOrdering() {
        assertCodecContradiction("""
                {"op":"MOVE","logicalId":"component-a","after":"component-b"},
                {"op":"DELETE","logicalId":"component-a"}""");
    }

    // ------------------------------------------------------------------ helpers

    private static ProposalOperation moveAfter(String logicalId, String anchor) {
        return ProposalOperation.move(
                ProposalIdentity.ofLogicalId(logicalId), MoveAnchorKind.AFTER, anchor);
    }

    private static CampaignProposalPatch patch(List<ProposalOperation> operations) {
        return new CampaignProposalPatch(
                ContractVersions.CURRENT,
                ProposalMode.EDIT,
                "campaign-001",
                new ProposalBaseRevisions(12L, "catalog-31", HASH, "policy-8", HASH),
                operations,
                ProposalClientContext.EMPTY);
    }

    private static void assertContradiction(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(ContractException.class)
                .satisfies(throwable -> {
                    ContractException failure = (ContractException) throwable;
                    assertThat(failure.code()).isEqualTo(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    assertThat(failure.getMessage())
                            .isEqualTo("Campaign proposal operations are invalid")
                            .doesNotContain("component-a", "component-b", SENTINEL);
                    assertThat(failure.details()).isEmpty();
                    assertThat(failure.getCause()).isNull();
                });
    }

    private void assertCodecContradiction(String operations) {
        String body = """
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"campaign-001",
                 "baseRevisions":{"campaignRevision":12,"catalogVersion":"catalog-31","catalogHash":"%s",
                                  "policyVersion":"policy-8","policyHash":"%s"},
                 "operations":[%s,{"op":"UPSERT","capability":"message.template","clientRef":"new-one",
                                  "value":{"text":"%s"}}]}"""
                .formatted(CATALOG_HASH, POLICY_HASH, operations.strip(), SENTINEL);

        assertThatThrownBy(() -> codec.parse(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ContractException.class)
                .satisfies(throwable -> {
                    ContractException failure = (ContractException) throwable;
                    assertThat(failure.code()).isEqualTo(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
                    assertThat(failure.getMessage())
                            .doesNotContain(SENTINEL, "component-a", "component-b", "new-one");
                    assertThat(failure.details()).isEmpty();
                    assertThat(failure.getCause()).isNull();
                });
    }
}