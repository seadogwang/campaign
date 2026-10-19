package com.company.campaign.runtime;

/**
 * The closed outcome of one processed node task.
 *
 * <p>Every value states exactly what committed, never what a later task should do about it: a message
 * acceptance, a reward success, a Provider result and a budget reservation are deliberately unrepresentable
 * here, because those belong to T23.
 */
public enum RuntimeNodeDisposition {

    /** The node succeeded locally and the Participant advanced to its exact successor. */
    ADVANCED,

    /** A frozen Wait node stored its deadline once and is waiting for its own wake task. */
    WAITING,

    /** The node succeeded and the Participant reached a terminal {@code COMPLETED} or {@code EXITED} state. */
    TERMINATED,

    /** A MESSAGE or REWARD node produced only bounded {@code ACTION_READY} handoff work for T23. */
    ACTION_READY,

    /** A bounded technical failure scheduled the next attempt under the frozen retry policy. */
    RETRY_SCHEDULED,

    /** The bounded attempts were exhausted or the failure was non-retryable, so the task is dead-lettered. */
    DEAD_LETTERED,

    /** The work stopped unresolved and the Participant needs a human decision. */
    NEEDS_REVIEW,

    /** Duplicate work was replayed hash-equal and reused the committed logical result. */
    DUPLICATE
}