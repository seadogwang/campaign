package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * The canonical facts a generic T19 capability preview card is bound by, and the one place that proves a card
 * belongs to the component it claims.
 *
 * <p>A card is a safe projection, but "safe" is not "correct": a card could name another component, another
 * capability version, a payload the compiler never normalized, a catalog reference set that does not exist or
 * a runtime schema this build does not run. Every one of those is a forged or stale claim about the resolved
 * component, and this class is the single definition of what it means for a card to be bound to one.
 *
 * <p>{@link #canonicalReferenceHash(List)} is part of the T19 pack contract as well: a pack builds its own card
 * and must pin the same canonical hash of the references it resolved, so the compiler and the pack can never
 * disagree about which reference set a card describes.
 */
public final class CapabilityPreviewCards {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private CapabilityPreviewCards() {
    }

    /**
     * The canonical hash of one component's resolved catalog references, or {@code null} when it resolved none.
     *
     * <p>The references are canonicalized exactly as a resolved component canonicalizes them — deterministic
     * order, no repeated resolution — so the hash depends on the resolved set and never on the order a contract
     * happened to produce it in. A component that resolves no reference has a genuinely absent hash rather than
     * the hash of an empty list, and a card may only carry the hash of an actually resolved reference set.
     */
    public static ContentHash canonicalReferenceHash(List<ResolvedCatalogReference> references) {
        List<ResolvedCatalogReference> canonical = ResolvedComponent.canonicalReferences(references);
        return canonical.isEmpty() ? null : ContentHash.sha256(CANONICAL_JSON.write(canonical));
    }

    /**
     * The invariant a card violates against one resolved component, or {@code null} when it binds exactly.
     *
     * <p>Only the facts a resolved component actually carries are re-proved here: its stable identity, its
     * capability code, its exact contract version, the hash of its normalized payload and the canonical hash of
     * its resolved references. The frozen runtime compatibility is a contract fact rather than a component fact,
     * so the compiler re-proves that separately against the contract version it resolved.
     */
    static String bindingInvariant(CapabilityPlanPreviewCard card, ResolvedComponent component) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(component, "component");
        if (!card.componentLogicalId().equals(component.logicalId())) {
            return "capabilityCardBindingComponent";
        }
        if (!card.capabilityCode().equals(component.capabilityCode())) {
            return "capabilityCardBindingCapabilityCode";
        }
        if (!card.contractVersion().equals(component.contractVersion())) {
            return "capabilityCardBindingContractVersion";
        }
        if (!card.normalizedPayloadHash().equals(component.value().contentHash())) {
            return "capabilityCardBindingPayloadHash";
        }
        if (!Objects.equals(card.resolvedCatalogReferenceHash(), canonicalReferenceHash(component.references()))) {
            return "capabilityCardBindingReferenceHash";
        }
        return null;
    }

    /** The deterministic identity of one card within a plan: the component it binds to and its closed kind. */
    static String cardIdentity(CapabilityPlanPreviewCard card) {
        return card.componentLogicalId() + '|' + card.cardKind();
    }
}