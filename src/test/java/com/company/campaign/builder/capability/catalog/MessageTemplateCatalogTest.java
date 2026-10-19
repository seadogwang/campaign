package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19 section 3: the frozen Message Template catalog.
 *
 * <p>Resolution accepts only a bounded reference code and yields the one exact
 * {@code logicalId}/{@code versionId}/content hash it names — never a "latest" entry — and a duplicate
 * reference code, a repeated identity or a hash conflict fails closed.
 */
class MessageTemplateCatalogTest {

    // ------------------------------------------------------------------ exact resolution

    @Test
    void resolutionYieldsTheOneExactVersionTheReferenceCodeNames() {
        MessageTemplateVersion welcome = version("tpl-welcome", "tpl-welcome", "tpl-welcome-v1", "EMAIL");
        MessageTemplateVersion receipt = version("tpl-receipt", "tpl-receipt", "tpl-receipt-v9", "SMS");
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of("tpl-catalog-1", List.of(welcome, receipt));

        assertThat(catalog.resolve("tpl-welcome")).contains(welcome);
        assertThat(catalog.resolve("tpl-receipt")).contains(receipt);
        // The exact version is pinned; there is no "latest" and no fallback to a neighbouring version.
        assertThat(welcome.resolvedReference()).isEqualTo(
                new ResolvedCatalogReference("tpl-welcome", "MESSAGE_TEMPLATE", "tpl-welcome", "tpl-welcome-v1"));
    }

    @Test
    void anAbsentOrNullReferenceCodeResolvesToNothing() {
        MessageTemplateCatalog catalog =
                MessageTemplateCatalog.of("tpl-catalog-1", List.of(version("tpl-welcome", "w", "w-v1", "EMAIL")));

        assertThat(catalog.resolve("tpl-absent")).isEmpty();
        assertThat(catalog.resolve(null)).isEmpty();
    }

    // ------------------------------------------------------------------ fail closed on duplicates and conflicts

    @Test
    void aDuplicatedReferenceCodeFailsClosed() {
        assertInvalid(() -> MessageTemplateCatalog.of("tpl-catalog-1", List.of(
                version("tpl-welcome", "w", "w-v1", "EMAIL"),
                version("tpl-welcome", "w2", "w2-v1", "SMS"))), "messageTemplateCatalogDuplicateReference");
    }

    @Test
    void aHashConflictOnOneIdentityFailsClosed() {
        // Same logicalId/versionId, two different content hashes: one identity cannot pin two contents.
        assertInvalid(() -> MessageTemplateCatalog.of("tpl-catalog-1", List.of(
                version("tpl-a", "shared", "shared-v1", "EMAIL"),
                version("tpl-b", "shared", "shared-v1", "SMS"))), "messageTemplateCatalogHashConflict");
    }

    // ------------------------------------------------------------------ hash binding

    @Test
    void theCatalogHasOneDeterministicByteFormAndHash() {
        MessageTemplateCatalog first = catalog();
        MessageTemplateCatalog second = catalog();

        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
        assertThat(first.catalogHash()).isEqualTo(second.catalogHash());
        assertThat(first.catalogHash()).isEqualTo(first.recomputeCatalogHash());
        assertThatCode(first::requireValidCatalogHash).doesNotThrowAnyException();
    }

    @Test
    void aChangeToAnyVersionOrTheVersionChangesTheCatalogHash() {
        ContentHash hash = catalog().recomputeCatalogHash();

        assertThat(MessageTemplateCatalog.of("tpl-catalog-2", List.of(
                version("tpl-a", "a", "a-v1", "EMAIL"))).recomputeCatalogHash()).isNotEqualTo(hash);
        assertThat(MessageTemplateCatalog.of("tpl-catalog-1", List.of(
                version("tpl-a", "a", "a-v2", "EMAIL"))).recomputeCatalogHash()).isNotEqualTo(hash);
        assertThat(MessageTemplateCatalog.of("tpl-catalog-1", List.of(
                version("tpl-a", "a", "a-v1", "SMS"))).recomputeCatalogHash()).isNotEqualTo(hash);
    }

    @Test
    void anAbsentOrTamperedCatalogHashPinFailsClosed() {
        MessageTemplateCatalog catalog = catalog();
        assertInvalid(() -> catalog.withCatalogHash(null).requireValidCatalogHash(),
                "messageTemplateCatalogHash");
        assertInvalid(() -> catalog.withCatalogHash(hash("tampered")).requireValidCatalogHash(),
                "messageTemplateCatalogHash");
    }

    // ------------------------------------------------------------------ the version declaration

    @Test
    void aVersionBindsItsExactIdentityFlagsAndDeterministicVariableSchema() {
        MessageTemplateVersion version = MessageTemplateVersion.of("tpl-welcome", "tpl-welcome", "tpl-welcome-v1",
                "EMAIL",
                List.of(new MessageTemplateVariable("lastName", CapabilityValueKind.STRING, false, 32),
                        new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16)),
                true, true, MessageClassification.PUBLIC);

        // The variable schema is frozen into one deterministic name order.
        assertThat(version.variables()).extracting(MessageTemplateVariable::name)
                .containsExactly("firstName", "lastName");
        assertThat(version.variable("firstName").required()).isTrue();
        assertThat(version.variable("missing")).isNull();
        assertThatCode(version::requireValidContentHash).doesNotThrowAnyException();
    }

    @Test
    void aVersionContentHashCoversEveryDeclaredFact() {
        ContentHash hash = version("tpl-a", "a", "a-v1", "EMAIL").recomputeContentHash();

        assertThat(MessageTemplateVersion.of("tpl-a", "a", "a-v2", "EMAIL",
                List.of(), true, true, MessageClassification.PUBLIC).recomputeContentHash()).isNotEqualTo(hash);
        assertThat(MessageTemplateVersion.of("tpl-a", "a", "a-v1", "SMS",
                List.of(), true, true, MessageClassification.PUBLIC).recomputeContentHash()).isNotEqualTo(hash);
        assertThat(MessageTemplateVersion.of("tpl-a", "a", "a-v1", "EMAIL",
                List.of(), false, true, MessageClassification.PUBLIC).recomputeContentHash()).isNotEqualTo(hash);
        assertThat(MessageTemplateVersion.of("tpl-a", "a", "a-v1", "EMAIL",
                List.of(), true, true, MessageClassification.INTERNAL).recomputeContentHash()).isNotEqualTo(hash);
    }

    @Test
    void anAbsentOrTamperedVersionHashPinFailsClosed() {
        MessageTemplateVersion version = version("tpl-a", "a", "a-v1", "EMAIL");
        assertInvalid(() -> version.withContentHash(null).requireValidContentHash(), "messageTemplateContentHash");
        assertInvalid(() -> version.withContentHash(hash("tampered")).requireValidContentHash(),
                "messageTemplateContentHash");
    }

    @Test
    void aDuplicatedVariableNameFailsClosed() {
        assertInvalid(() -> MessageTemplateVersion.of("tpl-a", "a", "a-v1", "EMAIL",
                List.of(new MessageTemplateVariable("name", CapabilityValueKind.STRING, false, 8),
                        new MessageTemplateVariable("name", CapabilityValueKind.STRING, true, 8)),
                true, true, MessageClassification.PUBLIC), "messageTemplateDuplicateVariable");
    }

    @Test
    void aReferenceEnumerationOrDurationVariableKindFailsClosed() {
        for (CapabilityValueKind kind : List.of(CapabilityValueKind.REFERENCE, CapabilityValueKind.ORDERED_ENUM,
                CapabilityValueKind.DURATION)) {
            assertInvalid(() -> new MessageTemplateVariable("value", kind, false,
                    MessageTemplateVariable.NO_MAX_LENGTH), "messageTemplateVariableType");
        }
    }

    @Test
    void aTextVariableRequiresAPositiveBoundedLengthAndANonTextVariableForbidsOne() {
        assertInvalid(() -> new MessageTemplateVariable("name", CapabilityValueKind.STRING, false, 0),
                "messageTemplateVariableMaxLength");
        assertInvalid(() -> new MessageTemplateVariable("name", CapabilityValueKind.STRING, false, 4097),
                "messageTemplateVariableMaxLength");
        assertInvalid(() -> new MessageTemplateVariable("count", CapabilityValueKind.INTEGER, false, 8),
                "messageTemplateVariableMaxLength");
        assertThatCode(() -> new MessageTemplateVariable("count", CapabilityValueKind.INTEGER, false,
                MessageTemplateVariable.NO_MAX_LENGTH)).doesNotThrowAnyException();
    }

    @Test
    void aMalformedVariableNameOrChannelOrClassificationFailsClosed() {
        assertInvalid(() -> new MessageTemplateVariable("First Name", CapabilityValueKind.STRING, false, 8),
                "messageTemplateVariableName");
        assertInvalid(() -> version("tpl-a", "a", "a-v1", "email"), "messageTemplateChannel");
        assertInvalid(() -> version("", "a", "a-v1", "EMAIL"), "messageTemplateReferenceCode");
        assertInvalid(() -> MessageClassification.parse("CONFIDENTIAL"), "messageTemplateClassification");
        assertInvalid(() -> MessageClassification.parse("RESTRICTED"), "messageTemplateClassification");
        assertInvalid(() -> MessageClassification.parse("public"), "messageTemplateClassification");
        assertThat(MessageClassification.parse("PUBLIC")).isEqualTo(MessageClassification.PUBLIC);
    }

    @Test
    void theCatalogRedactsItsDiagnostics() {
        assertThat(catalog().toString())
                .contains("tpl-catalog-1", "templates=1");
    }

    // ------------------------------------------------------------------ fixtures

    private static MessageTemplateCatalog catalog() {
        return MessageTemplateCatalog.of("tpl-catalog-1",
                List.of(version("tpl-a", "a", "a-v1", "EMAIL")));
    }

    private static MessageTemplateVersion version(
            String referenceCode, String logicalId, String versionId, String channel) {
        List<MessageTemplateVariable> variables = new ArrayList<>();
        variables.add(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16));
        return MessageTemplateVersion.of(referenceCode, logicalId, versionId, channel, variables,
                true, true, MessageClassification.PUBLIC);
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
                });
    }
}