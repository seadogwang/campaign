package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One immutable Message Template Version a frozen catalog owns.
 *
 * <p>It freezes the exact {@code referenceCode} -> {@code logicalId}/{@code versionId} identity and the
 * canonical content hash of the version, the channel machine code it delivers on, its deterministic
 * variable schema, its enabled/runtime-available flags and its authoring classification. Resolution is by
 * exact reference code only: there is no display name, no {@code latest} alias and no mutable current
 * pointer anywhere in it.
 *
 * <p>It deliberately never exposes the template body, a provider credential, an endpoint or any provider
 * configuration. Those are not representable here at all, so a pack that validates against a version cannot
 * read — and cannot leak — a template's content.
 *
 * <p>The version has exactly one canonical byte form and one content hash. {@link #of} is the only way to
 * build a version carrying a correct {@code contentHash}, and {@link #requireValidContentHash()} re-derives
 * it from the value, so a stale or tampered pin is refused rather than trusted. The variable schema is
 * frozen into one deterministic name order with no duplicate names, so two declarations of the same
 * variables always produce identical bytes and hash.
 */
public record MessageTemplateVersion(
        String referenceCode,
        String logicalId,
        String versionId,
        ContentHash contentHash,
        String channel,
        List<MessageTemplateVariable> variables,
        boolean enabled,
        boolean runtimeAvailable,
        MessageClassification classification) {

    /**
     * Largest number of variables one template version may declare.
     *
     * <p>It is deliberately the same bound the compiler already applies to a payload variable object, so a
     * template can never declare more variables than a payload is permitted to supply.
     */
    public static final int MAX_VARIABLES = 64;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CHANNEL = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public MessageTemplateVersion {
        if (referenceCode == null || !IDENTIFIER.matcher(referenceCode).matches()) {
            throw CatalogSafety.invalid("messageTemplateReferenceCode");
        }
        if (logicalId == null || !IDENTIFIER.matcher(logicalId).matches()) {
            throw CatalogSafety.invalid("messageTemplateLogicalId");
        }
        if (versionId == null || !IDENTIFIER.matcher(versionId).matches()) {
            throw CatalogSafety.invalid("messageTemplateVersionId");
        }
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw CatalogSafety.invalid("messageTemplateChannel");
        }
        if (classification == null) {
            throw CatalogSafety.invalid("messageTemplateClassification");
        }
        variables = canonicalVariables(variables);
    }

    /**
     * Builds one template version and stamps it with its own canonical content hash.
     *
     * <p>The hash covers the version with {@code contentHash} absent, so the pin can never be
     * self-referential.
     */
    public static MessageTemplateVersion of(
            String referenceCode,
            String logicalId,
            String versionId,
            String channel,
            List<MessageTemplateVariable> variables,
            boolean enabled,
            boolean runtimeAvailable,
            MessageClassification classification) {
        MessageTemplateVersion unhashed = new MessageTemplateVersion(referenceCode, logicalId, versionId, null,
                channel, variables, enabled, runtimeAvailable, classification);
        return unhashed.withContentHash(unhashed.recomputeContentHash());
    }

    /** The same version carrying the given content hash pin. */
    public MessageTemplateVersion withContentHash(ContentHash hash) {
        return new MessageTemplateVersion(referenceCode, logicalId, versionId, hash, channel, variables,
                enabled, runtimeAvailable, classification);
    }

    /** The canonical UTF-8 bytes of this version with the content hash pin excluded. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(withContentHash(null));
    }

    /** The canonical content hash of this version with the content hash pin excluded. */
    public ContentHash recomputeContentHash() {
        return CANONICAL_JSON.hash(withContentHash(null));
    }

    /** Refuses a version whose declared content hash pin is absent or does not match its own content. */
    public void requireValidContentHash() {
        if (contentHash == null || !contentHash.equals(recomputeContentHash())) {
            throw CatalogSafety.invalid("messageTemplateContentHash");
        }
    }

    /** The one stable catalog reference this version resolves to; never a display name or a "latest" alias. */
    public ResolvedCatalogReference resolvedReference() {
        return new ResolvedCatalogReference(referenceCode, MessageTemplateCatalog.ENTITY_KIND, logicalId, versionId);
    }

    /** The declared variable with the given name, or {@code null} when the schema declares none. */
    public MessageTemplateVariable variable(String name) {
        if (name == null) {
            return null;
        }
        for (MessageTemplateVariable variable : variables) {
            if (variable.name().equals(name)) {
                return variable;
            }
        }
        return null;
    }

    /**
     * Proves one variable schema is bounded, non-null and duplicate-free, then freezes it into one
     * deterministic name order.
     *
     * <p>A repeated variable name fails closed rather than being silently collapsed, so a caller cannot
     * smuggle two different declarations under one name.
     */
    private static List<MessageTemplateVariable> canonicalVariables(List<MessageTemplateVariable> variables) {
        if (variables == null || variables.size() > MAX_VARIABLES) {
            throw CatalogSafety.invalid("messageTemplateVariables");
        }
        List<MessageTemplateVariable> copy = new ArrayList<>(variables.size());
        Set<String> names = new HashSet<>(variables.size());
        for (MessageTemplateVariable variable : variables) {
            if (variable == null) {
                throw CatalogSafety.invalid("messageTemplateVariables");
            }
            if (!names.add(variable.name())) {
                throw CatalogSafety.invalid("messageTemplateDuplicateVariable");
            }
            copy.add(variable);
        }
        copy.sort(Comparator.comparing(MessageTemplateVariable::name));
        return ImmutableValues.freezeList(copy);
    }

    /** Redacted diagnostics: only bounded machine facts, never a template body or a credential. */
    @Override
    public String toString() {
        return "MessageTemplateVersion[referenceCode=" + referenceCode
                + ", logicalId=" + logicalId
                + ", versionId=" + versionId
                + ", channel=" + channel
                + ", variables=" + variables.size()
                + ", enabled=" + enabled
                + ", runtimeAvailable=" + runtimeAvailable
                + ", classification=" + classification + ']';
    }
}