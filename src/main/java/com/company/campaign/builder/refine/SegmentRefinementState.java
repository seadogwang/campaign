package com.company.campaign.builder.refine;

/**
 * The closed state machine of the T12 refinement orchestrator.
 *
 * <p>The happy path is
 * {@code REFINING → DISCOVERING → APPLYING_PATCH → VALIDATING → PLANNING → PREVIEWING → READY_FOR_PREVIEW}.
 * {@code REFINING} is the start state; {@code DISCOVERING} is the admitted {@code metadata.discover} Tool;
 * {@code APPLYING_PATCH} is the host-owned, non-Tool structural stage that runs the deterministic editor; and
 * the remaining states are the genuine T07, T08 and T09C Tools.
 *
 * <p>Terminal outcomes are exactly {@code READY_FOR_PREVIEW}, {@code DENIED} and {@code INVALID}. There is
 * deliberately no {@code NEEDS_INPUT} and no {@code UNSUPPORTED}: a refinement starts from a document that
 * already exists and edits it structurally, so there is no unresolved Intent to clarify and no missing
 * capability to report. No state names a saved, applied, published, active or live-ready outcome, because this
 * task performs none of those actions.
 */
public enum SegmentRefinementState {

    /** The start state: host-owned validation of the pinned request, before any protected work. */
    REFINING,

    /** Discover the permitted Metadata through the admitted Tool boundary. */
    DISCOVERING,

    /** Apply the deterministic patch and re-prove the document's Metadata binding, outside any Tool call. */
    APPLYING_PATCH,

    /** Validate and canonicalize the refined document through the admitted T07 Tool. */
    VALIDATING,

    /** Plan the validated document through the admitted T08 Tool. */
    PLANNING,

    /** Run the genuine T09C Safe Preview through the admitted Tool, with samples disabled. */
    PREVIEWING,

    /** Terminal success: the refined document has safe Preview evidence and, when honestly grounded, an explanation. */
    READY_FOR_PREVIEW,

    /** Terminal: the capability exists but this principal or DataScope cannot use it. */
    DENIED,

    /** Terminal: the request was understood but violates a contract, or a protected stage refused it. */
    INVALID;

    /** True for the three terminal outcomes and false for every in-flight state. */
    public boolean isTerminal() {
        return this == READY_FOR_PREVIEW || this == DENIED || this == INVALID;
    }
}