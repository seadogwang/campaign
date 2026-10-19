package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 1: the strict Proposal codec rejects malformed, duplicate, unknown, null, oversized and deep
 * input, and every reserved trusted/runtime/plan/ChangeSet field at every nesting level — without ever
 * echoing a payload value.
 */
class CampaignProposalCodecStrictnessTest {

    private static final String CATALOG_HASH = "sha256:" + "a".repeat(64);
    private static final String POLICY_HASH = "sha256:" + "b".repeat(64);
    private static final String SENTINEL = "SENTINEL-VALUE-DO-NOT-ECHO";

    private final CampaignProposalCodec codec = new CampaignProposalCodec();

    // ------------------------------------------------------------------ acceptance

    @Test
    void parsesAWellFormedEditProposal() {
        CampaignProposalPatch patch = codec.parse(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"message-main","value":{"text":"hi"}},
                {"op":"UPSERT","capability":"message.template","clientRef":"message-new","value":{"text":"new"}},
                {"op":"DELETE","logicalId":"flow-old"},
                {"op":"MOVE","logicalId":"message-main","before":"message-new"}"""));

        assertThat(patch.mode()).isEqualTo(ProposalMode.EDIT);
        assertThat(patch.campaignId()).isEqualTo("campaign-001");
        assertThat(patch.baseRevisions().campaignRevision()).isEqualTo(12L);
        assertThat(patch.schemaVersion()).isEqualTo(ContractVersions.CURRENT);
        assertThat(patch.operations()).hasSize(4);
        assertThat(patch.clientRefs()).containsExactly("message-new");
        assertThat(patch.operations().get(2).kind()).isEqualTo(ProposalOperationKind.DELETE);
        assertThat(patch.operations().get(3).anchor().kind()).isEqualTo(MoveAnchorKind.BEFORE);
    }

    @Test
    void parsesACreateProposalWithoutACampaignId() {
        CampaignProposalPatch patch = codec.parse("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"catalog-31","catalogHash":"%s",
                                  "policyVersion":"policy-8","policyHash":"%s"},
                 "operations":[{"op":"UPSERT","capability":"message.template","clientRef":"message-new","value":{}}],
                 "clientContext":{"locale":"zh-CN"}}""".formatted(CATALOG_HASH, POLICY_HASH));
        assertThat(patch.isCreate()).isTrue();
        assertThat(patch.campaignId()).isNull();
        assertThat(patch.baseRevisions().campaignRevision()).isNull();
        assertThat(patch.clientRefs()).containsExactly("message-new");
        assertThat(patch.clientContext().locale().value()).isEqualTo("zh-CN");
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    void rejectsAbsentMalformedAndEmptyBodies() {
        assertCode(codec, null, ProposalErrorCodes.PROPOSAL_MISSING);
        assertCode(codec, new byte[0], ProposalErrorCodes.PROPOSAL_MISSING);
        assertCode(codec, utf8(""), ProposalErrorCodes.PROPOSAL_MISSING);
        assertCode(codec, utf8("   "), ProposalErrorCodes.PROPOSAL_JSON_MALFORMED);
        assertCode(codec, utf8("{"), ProposalErrorCodes.PROPOSAL_JSON_MALFORMED);
        assertCode(codec, utf8("[1,2,3]"), ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8("null"), ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
    }

    @Test
    void rejectsNonUtf8Bytes() {
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFE, '{', '}'};
        assertCode(codec, bytes, ProposalErrorCodes.PROPOSAL_ENCODING_INVALID);
    }

    @Test
    void rejectsDuplicateObjectKeysAtAnyNestingLevel() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_DUPLICATE_KEY);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[{"op":"UPSERT","capability":"message.template","clientRef":"r",
                                "value":{"a":1,"a":2}}]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_DUPLICATE_KEY);
    }

    @Test
    void rejectsUnknownAndAlternatelyCasedFields() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE","extra":1,
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","Mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"upsert","capability":"message.template","clientRef":"r","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r","value":{},"LogicalId":"x"}""")),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
    }

    @Test
    void rejectsNullAndMissingRequiredFields() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r","value":null}""")),
                ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":null,"value":{}}""")),
                ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","clientRef":"r","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"}}"""
                .formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        assertCode(codec, utf8("""
                {"mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
    }

    @Test
    void rejectsUnsupportedSchemaVersionAndMode() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.2","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"edit",
                 "baseRevisions":{"campaignRevision":12,"catalogVersion":"c","catalogHash":"%s",
                                  "policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_MODE_INVALID);
    }

    @Test
    void rejectsCampaignIdThatBelongsToTheWrongMode() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE","campaignId":"campaign-001",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"EDIT",
                 "baseRevisions":{"campaignRevision":12,"catalogVersion":"c","catalogHash":"%s",
                                  "policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"has spaces!",
                 "baseRevisions":{"campaignRevision":12,"catalogVersion":"c","catalogHash":"%s",
                                  "policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
    }

    @Test
    void rejectsARevisionOverrideOutsideBaseRevisions() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE","revision":99,
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"campaign-001",
                 "baseRevisions":{"campaignRevision":12,"campaignRevisionOverride":13,
                                  "catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
    }

    @Test
    void rejectsAnInvertedOrMissingBaseRevision() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"campaign-001",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE","campaignRevision":1,
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"campaign-001",
                 "baseRevisions":{"campaignRevision":0,"catalogVersion":"c","catalogHash":"%s",
                                  "policyVersion":"p","policyHash":"%s"},
                 "operations":[]}""".formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
    }

    @Test
    void rejectsAMalformedContentHashPin() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"not-a-hash","policyVersion":"p",
                                  "policyHash":"%s"},
                 "operations":[]}""".formatted(POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
    }

    @Test
    void rejectsAnInvalidClientPresentationContext() {
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[],"clientContext":{"locale":"not a locale"}}"""
                .formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID);
        assertCode(codec, utf8("""
                {"schemaVersion":"1.3","mode":"CREATE",
                 "baseRevisions":{"catalogVersion":"c","catalogHash":"%s","policyVersion":"p","policyHash":"%s"},
                 "operations":[],"clientContext":{"timezone":"Asia/Shanghai"}}"""
                .formatted(CATALOG_HASH, POLICY_HASH)),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
    }

    // ------------------------------------------------------------------ identity rules

    @Test
    void rejectsAmbiguousOrAbsentIdentity() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"a","clientRef":"b","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
    }

    @Test
    void rejectsIdentityOutsideItsBoundedGrammar() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"has spaces","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"%s","value":{}}""".formatted("x".repeat(200)))),
                ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
    }

    @Test
    void rejectsADeleteThatCarriesAValueOrAClientReference() {
        assertCode(codec, utf8(edit("""
                {"op":"DELETE","logicalId":"flow-old","value":{"secret":1}}""")),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"DELETE","clientRef":"fresh"}""")),
                ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
    }

    @Test
    void rejectsDuplicateAndContradictoryOperations() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"a","value":{}},
                {"op":"UPSERT","capability":"message.template","logicalId":"a","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"DELETE","logicalId":"a"},{"op":"UPSERT","capability":"message.template","logicalId":"a","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"DELETE","logicalId":"a"},{"op":"MOVE","logicalId":"a","before":"b"}""")),
                ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r","value":{}},
                {"op":"UPSERT","capability":"message.template","clientRef":"r","value":{}}""")),
                ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
    }

    @Test
    void rejectsAnUndeclaredClientReference() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"a","value":{}},
                {"op":"MOVE","clientRef":"undeclared","before":"a"}""")),
                ProposalErrorCodes.PROPOSAL_CLIENT_REF_CONFLICT);
    }

    @Test
    void rejectsSelfAnchorsAndCyclicOrdering() {
        assertCode(codec, utf8(edit("""
                {"op":"MOVE","logicalId":"a","before":"a"}""")),
                ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"MOVE","logicalId":"a","before":"b"},{"op":"MOVE","logicalId":"b","before":"a"}""")),
                ProposalErrorCodes.PROPOSAL_ORDER_CYCLE);
        assertCode(codec, utf8(edit("""
                {"op":"MOVE","logicalId":"a","before":"b"},
                {"op":"MOVE","logicalId":"b","before":"c"},
                {"op":"MOVE","logicalId":"c","before":"a"}""")),
                ProposalErrorCodes.PROPOSAL_ORDER_CYCLE);
    }

    @Test
    void rejectsAMoveWithZeroOrTwoAnchors() {
        assertCode(codec, utf8(edit("""
                {"op":"MOVE","logicalId":"a"}""")),
                ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"MOVE","logicalId":"a","before":"b","after":"c"}""")),
                ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","logicalId":"a","value":{},"before":"b"}""")),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
    }

    // ------------------------------------------------------------------ reserved fields

    @ParameterizedTest
    @ValueSource(strings = {
            "tenantId", "tenant_id", "TENANT-ID", "tenant.id",
            "programId", "userId", "memberId", "membershipId", "dataScopeId", "scope",
            "permissions", "roles", "authorizationDecision", "authorization", "allowed", "denied",
            "revision", "revisionOverride", "baseRevisions",
            "status", "publishedId", "publishedVersion",
            "planId", "planHash", "manifest", "changeSet", "resolvedManifest",
            "sql", "tableName", "schemaName", "storageCatalog", "jdbc",
            "createdAt", "expiresAt", "expiry", "compiledAt",
            "provider", "credentials", "apiKey", "secret", "token",
            "apply", "publish", "save", "applyFlag"})
    void rejectsEveryReservedFieldInsideAPayload(String reservedName) {
        String body = edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"%s":"%s"}}""".formatted(reservedName, SENTINEL));
        assertCode(codec, utf8(body), ProposalErrorCodes.PROPOSAL_RESERVED_FIELD);
    }

    @Test
    void rejectsReservedFieldsNestedInsideObjectsAndArrays() {
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"outer":{"inner":{"tenantId":"t"}}}}""")),
                ProposalErrorCodes.PROPOSAL_RESERVED_FIELD);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"items":[{"ok":1},{"permissions":[]}]}}""")),
                ProposalErrorCodes.PROPOSAL_RESERVED_FIELD);
        assertCode(codec, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"deep":[{"level":[{"planId":"p"}]}]}}""")),
                ProposalErrorCodes.PROPOSAL_RESERVED_FIELD);
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void rejectsOversizedInput() {
        CampaignProposalCodec bounded = new CampaignProposalCodec(new ProposalLimits(512, 24, 1024, 256));
        assertCode(bounded, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"blob":"%s"}}""".formatted("x".repeat(600)))),
                ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsTooDeepInput() {
        CampaignProposalCodec bounded = new CampaignProposalCodec(new ProposalLimits(262_144, 5, 1024, 256));
        assertCode(bounded, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"a":{"b":{"c":{"d":{"e":1}}}}}}""")),
                ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsTooWideAndTooManyOperations() {
        CampaignProposalCodec bounded = new CampaignProposalCodec(new ProposalLimits(262_144, 24, 4, 256));
        assertCode(bounded, utf8(edit("""
                {"op":"UPSERT","capability":"message.template","clientRef":"r",
                 "value":{"items":[1,2,3,4,5]}}""")),
                ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);

        CampaignProposalCodec fewOps = new CampaignProposalCodec(new ProposalLimits(262_144, 24, 1024, 2));
        assertCode(fewOps, utf8(edit("""
                {"op":"DELETE","logicalId":"a"},{"op":"DELETE","logicalId":"b"},{"op":"DELETE","logicalId":"c"}""")),
                ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
    }

    // ------------------------------------------------------------------ non-disclosure

    @Test
    void failuresNeverEchoPayloadValuesOrFieldNames() {
        ContractException failure = catchThrowableOfType(
                () -> codec.parse(utf8(edit("""
                        {"op":"UPSERT","capability":"message.template","clientRef":"%s",
                         "value":{"tenantId":"%s"}}""".formatted(SENTINEL, SENTINEL)))),
                ContractException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(ProposalErrorCodes.PROPOSAL_RESERVED_FIELD);
        assertThat(failure.getMessage()).doesNotContain(SENTINEL).doesNotContain("tenantId");
        assertThat(failure.details().toString()).doesNotContain(SENTINEL).doesNotContain("tenantId");
    }

    @Test
    void everyFailureCarriesAStableBoundedCodeAndFixedProse() {
        ContractException failure = catchThrowableOfType(
                () -> codec.parse(utf8("{")), ContractException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(ProposalErrorCodes.PROPOSAL_JSON_MALFORMED);
        assertThat(failure.details()).isEmpty();
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("Campaign proposal request is not valid JSON");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCode(CampaignProposalCodec codec, byte[] body, String expectedCode) {
        assertThatThrownBy(() -> codec.parse(body))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(expectedCode);
    }

    private static String edit(String operations) {
        return """
                {"schemaVersion":"1.3","mode":"EDIT","campaignId":"campaign-001",
                 "baseRevisions":{"campaignRevision":12,"catalogVersion":"catalog-31","catalogHash":"%s",
                                  "policyVersion":"policy-8","policyHash":"%s"},
                 "operations":[%s]}""".formatted(CATALOG_HASH, POLICY_HASH, operations.strip());
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}