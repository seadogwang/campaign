package com.company.campaign.segment.resolution;

import com.company.campaign.segment.intent.SegmentIntent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a clarification choice by re-resolving the current exact inputs.
 *
 * <p>A choice is accepted only when the resolution id, base Intent hash, Metadata snapshot pin, policy pin,
 * registered-default registry pin, question code, clause and current option set all still match. Any change
 * to the Intent, the permitted catalog, the policy, the registry revision, the question or the options
 * produces a stable stale-clarification failure; the old choice is never applied. A selected option must
 * still be compatible, and applying it returns a new deterministic resolution rather than a DSL or a
 * mutation.
 *
 * <p>It handles only definition-selection questions: a grouping, currency, NULL, window, relation-scope or
 * metric-meaning clarification carries no options and any definition choice against it fails as stale, so a
 * global ambiguity can never be "answered" by selecting a definition for one of its clauses.
 */
public final class ClarificationChoiceService {

    private final IntentResolver resolver;

    public ClarificationChoiceService(IntentResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Re-resolves the current inputs and applies the choice when every receipt still matches. */
    public ClarificationChoiceResult apply(SegmentIntent intent, ClarificationChoice choice) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(choice, "choice");

        ResolutionResult current = resolver.resolve(intent);
        Clarification clarification = current.clarification();
        if (clarification == null) {
            throw stale("NO_CLARIFICATION");
        }
        if (!choice.baseIntentHash().equals(current.baseIntentHash())
                || !choice.baseIntentHash().equals(com.company.campaign.segment.intent.SegmentIntentHasher.hash(intent))) {
            throw stale("INTENT");
        }
        if (!choice.pins().equals(current.pins())) {
            throw stale("PINS");
        }
        if (!choice.resolutionId().equals(clarification.resolutionId())) {
            throw stale("RESOLUTION_ID");
        }
        if (choice.questionCode() != clarification.questionCode()
                || !choice.clauseId().equals(clarification.clauseId())) {
            throw stale("QUESTION");
        }
        // Only a definition-selection question can be answered with a definition option. A grouping,
        // currency, NULL, window, relation-scope or metric-meaning clarification is not a definition choice:
        // attempting one fails as stale without changing a resolution, even for the first clause.
        if (!clarification.questionCode().isDefinitionSelection()) {
            throw stale("QUESTION_NOT_SELECTABLE");
        }
        String definitionId = null;
        for (ClarificationOption option : clarification.options()) {
            if (option.optionId().equals(choice.optionId())) {
                definitionId = option.definitionId();
            }
        }
        if (definitionId == null) {
            throw stale("OPTION");
        }
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put(clarification.clauseId(), definitionId);
        ClarificationSelectionReceipt receipt = new ClarificationSelectionReceipt(
                clarification.resolutionId(), clarification.clauseId(), choice.optionId());
        ResolutionResult applied = withReceipts(
                resolver.resolve(intent, selections), List.of(receipt));
        return new ClarificationChoiceResult(applied, clarification.clauseId(), choice.optionId());
    }

    /**
     * Continues through zero or more exact current definition-selection clarifications.
     *
     * <p>Every callback is made only after re-resolving with the accumulated validated choices. The returned
     * option id must belong to that exact clarification; a missing answer stops safely at {@code NEEDS_INPUT},
     * while an unknown option is rejected. Non-definition questions are returned to the host unchanged and
     * can never be bypassed through this port.
     */
    public ResolutionResult continueResolution(
            SegmentIntent intent,
            ClarificationSelectionPort selectionPort) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(selectionPort, "selectionPort");

        Map<String, String> selections = new LinkedHashMap<>();
        List<ClarificationSelectionReceipt> receipts = new ArrayList<>();
        // Each clause, each nested scope filter and each audience reference can consume one answer, and an
        // audience reference may legitimately need two: which audience, then which immutable version of it.
        int maximumSelections = intent.clauses().size()
                + intent.clauses().stream().mapToInt(clause -> clause.scopeFilters().size()).sum()
                + 2 * intent.audienceReferences().size();
        for (int index = 0; index <= maximumSelections; index++) {
            ResolutionResult current = resolver.resolve(intent, selections);
            if (current.disposition() != com.company.campaign.contracts.Disposition.NEEDS_INPUT) {
                return withReceipts(current, receipts);
            }
            Clarification clarification = current.clarification();
            if (clarification == null || !clarification.questionCode().isDefinitionSelection()) {
                return withReceipts(current, receipts);
            }
            String optionId = selectionPort.select(clarification);
            if (optionId == null) {
                return withReceipts(current, receipts);
            }
            String definitionId = null;
            for (ClarificationOption option : clarification.options()) {
                if (option.optionId().equals(optionId)) {
                    definitionId = option.definitionId();
                    break;
                }
            }
            if (definitionId == null || selections.containsKey(clarification.clauseId())) {
                throw stale("OPTION");
            }
            selections.put(clarification.clauseId(), definitionId);
            receipts.add(new ClarificationSelectionReceipt(
                    clarification.resolutionId(), clarification.clauseId(), optionId));
        }
        throw new ResolutionException(
                ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                "Clarification selection exceeded the bounded resolution targets");
    }

    /** Replays and re-proves an ordered selection chain against the exact current inputs. */
    public ResolutionResult replay(
            SegmentIntent intent,
            List<ClarificationSelectionReceipt> receipts) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(receipts, "receipts");
        Map<String, String> selections = new LinkedHashMap<>();
        List<ClarificationSelectionReceipt> proven = new ArrayList<>();
        for (ClarificationSelectionReceipt receipt : receipts) {
            ResolutionResult current = resolver.resolve(intent, selections);
            Clarification clarification = current.clarification();
            if (clarification == null
                    || !clarification.questionCode().isDefinitionSelection()
                    || !clarification.resolutionId().equals(receipt.resolutionId())
                    || !clarification.clauseId().equals(receipt.selectionTarget())
                    || selections.containsKey(receipt.selectionTarget())) {
                throw stale("RECEIPT");
            }
            String definitionId = null;
            for (ClarificationOption option : clarification.options()) {
                if (option.optionId().equals(receipt.optionId())) {
                    definitionId = option.definitionId();
                    break;
                }
            }
            if (definitionId == null) {
                throw stale("OPTION");
            }
            selections.put(receipt.selectionTarget(), definitionId);
            proven.add(receipt);
        }
        return withReceipts(resolver.resolve(intent, selections), proven);
    }

    private static ResolutionResult withReceipts(
            ResolutionResult result,
            List<ClarificationSelectionReceipt> receipts) {
        return new ResolutionResult(
                result.disposition(), result.baseIntentHash(), result.pins(), result.clauses(),
                result.clarification(), receipts, result.references());
    }

    private static ResolutionException stale(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        return new ResolutionException(
                ResolutionErrorCodes.STALE_CLARIFICATION,
                "The clarification choice no longer matches the current exact inputs",
                details);
    }
}
