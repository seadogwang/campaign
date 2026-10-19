package com.company.campaign.evaluation.segment.reference;

import java.util.List;
import java.util.Set;

/**
 * The closed set of terminal outcomes a reference-corpus evaluation may observe.
 *
 * <p>Each constant declares the scenarios it may be used in, so an expectation that pairs an outcome with a
 * scenario it cannot occur in is a corpus defect rather than an unchecked claim. The set deliberately keeps
 * apart outcomes that demand different reactions — a denial from an unavailability, a refused deletion from
 * a successful one — because collapsing them would make "we could not use it" indistinguishable from
 * "there was nothing to use".
 */
public enum ReferenceOutcome {

    /** A reference-bearing document produced its canonical AST and a clean reference graph. */
    CLEAN(Set.of(ReferenceScenario.REFERENCE_DOCUMENT)),

    /** A reference-bearing document was rejected: at least one blocking reference finding was reported. */
    REJECTED(Set.of(ReferenceScenario.REFERENCE_DOCUMENT)),

    /** One eligible immutable published version was pinned, with its resolution evidence recorded. */
    RESOLVED(Set.of(ReferenceScenario.REFERENCE_RESOLUTION)),

    /** Several audiences or several eligible versions matched, so one structured question was asked. */
    NEEDS_INPUT(Set.of(ReferenceScenario.REFERENCE_RESOLUTION)),

    /** No candidate was visible, or a visible audience had no eligible published version. */
    UNSUPPORTED(Set.of(ReferenceScenario.REFERENCE_RESOLUTION)),

    /** A visible candidate may no longer be referenced; nothing beyond what discovery showed is disclosed. */
    DENIED(Set.of(ReferenceScenario.REFERENCE_RESOLUTION)),

    /** The request was understood but refused as internally incompatible, such as an answer to no question. */
    INVALID(Set.of(ReferenceScenario.REFERENCE_RESOLUTION)),

    /** Local fixture membership answered for the exact pin. */
    PRESENT(Set.of(ReferenceScenario.REFERENCE_MEMBERSHIP)),

    /** Local fixture membership had nothing for the exact pin, which is not an empty member set. */
    ABSENT(Set.of(ReferenceScenario.REFERENCE_MEMBERSHIP)),

    /** The exact version's immutable bytes were removed and its availability is now deleted. */
    DELETED(Set.of(ReferenceScenario.REFERENCE_RETENTION)),

    /** A deletion was refused because at least one retention hold is still active; nothing changed. */
    REJECTED_ACTIVE_HOLD(Set.of(ReferenceScenario.REFERENCE_RETENTION)),

    /** A deletion was refused because the host retention policy keeps unheld referenced versions alive. */
    RETAINED_BY_POLICY(Set.of(ReferenceScenario.REFERENCE_RETENTION)),

    /** The retention store never held the exact version, so there was nothing to delete. */
    UNKNOWN_TARGET(Set.of(ReferenceScenario.REFERENCE_RETENTION));

    private final Set<ReferenceScenario> scenarios;

    ReferenceOutcome(Set<ReferenceScenario> scenarios) {
        this.scenarios = scenarios;
    }

    /** True when this outcome may legitimately be observed in the given scenario. */
    public boolean appliesTo(ReferenceScenario scenario) {
        return scenarios.contains(scenario);
    }

    /** Every outcome valid for one scenario, in declaration order. */
    public static List<ReferenceOutcome> forScenario(ReferenceScenario scenario) {
        return List.of(values()).stream().filter(outcome -> outcome.appliesTo(scenario)).toList();
    }
}