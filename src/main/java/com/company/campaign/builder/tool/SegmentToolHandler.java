package com.company.campaign.builder.tool;

import com.company.campaign.contracts.ToolInput;

/**
 * The one host-owned implementation boundary of an allowed Tool.
 *
 * <p>A handler receives only the host-owned {@link SegmentToolRunContext} and the model-supplied
 * {@link ToolInput}. It cannot receive a trusted fact, choose a backend, register itself or widen the Skill
 * allowlist. A handler must fail closed with a stable code when it can decide the bounded outcome; when an
 * injected component throws unexpectedly the invoker catches the unchecked failure and reports one bounded
 * {@link SegmentToolHandler#FAILURE_CODE} without echoing the message.
 *
 * <p>The handler is also where the protected operation really runs: it drives the accepted T07/T08/T09C/T10
 * component and returns the accepted typed artifact through {@link SegmentToolExecution}, so no protected
 * implementation can be reached outside an admitted call.
 */
@FunctionalInterface
public interface SegmentToolHandler {

    /** Executes one Tool request against the host run evidence. */
    SegmentToolExecution execute(SegmentToolRunContext run, ToolInput input);

    /** The stable code reported when a handler throws instead of failing closed. */
    String FAILURE_CODE = SegmentToolErrorCodes.TOOL_EXECUTION_FAILED;
}