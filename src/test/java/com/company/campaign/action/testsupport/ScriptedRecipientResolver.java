package com.company.campaign.action.testsupport;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ActionRecipientResolverPort;
import com.company.campaign.action.ActionRecipientTarget;
import com.company.campaign.action.ActionSupport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A deterministic host recipient resolver for T23 tests.
 *
 * <p>It returns an opaque bounded reference and an evidence hash derived from the exact persisted owner and
 * frozen node identity, so no member address ever enters the action layer and the same action always resolves
 * to the same target. It can be made to refuse, to throw, or to answer with evidence that changes between
 * calls, so the fail-closed and duplicate-key paths can be proved without a real directory.
 */
public final class ScriptedRecipientResolver implements ActionRecipientResolverPort {

    private final Set<String> unresolved = new LinkedHashSet<>();
    private final List<String> calls = new java.util.ArrayList<>();
    private boolean throwsAlways;
    private int resolveCount;

    /** Refuses every resolution for one exact Participant identity. */
    public ScriptedRecipientResolver refusing(String participantId) {
        unresolved.add(participantId);
        return this;
    }

    /** Throws instead of answering. */
    public ScriptedRecipientResolver throwing() {
        this.throwsAlways = true;
        return this;
    }

    /** How many resolutions this resolver was actually asked for. */
    public int resolveCount() {
        return resolveCount;
    }

    /** Every Participant identity this resolver was asked about, in call order. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public Optional<ActionRecipientTarget> resolve(
            String tenantId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String channelCode) {
        resolveCount++;
        calls.add(participantId);
        if (throwsAlways) {
            throw new IllegalStateException("scripted recipient resolver failure");
        }
        if (unresolved.contains(participantId)) {
            return Optional.empty();
        }
        String reference = ActionSupport.derivedId("recipient", List.of(tenantId, participantId, nodeLogicalId));
        return Optional.of(new ActionRecipientTarget(
                reference,
                ActionSupport.seedHash(
                        tenantId + '|' + participantId + '|' + nodeLogicalId + '|' + actionKind.code())));
    }

    /** Redacted diagnostics: the resolver holds no owner value. */
    @Override
    public String toString() {
        return "ScriptedRecipientResolver[calls=" + resolveCount + ']';
    }
}