package com.company.campaign.builder.assembly;

/**
 * The one host-owned implementation boundary of an available Campaign Tool.
 *
 * <p>A handler receives only the host-owned {@link CampaignToolRunContext} and the model-supplied typed
 * {@link CampaignToolRequest}. It cannot receive a trusted fact, choose a backend, register itself or widen the
 * catalog allowlist. A handler must fail closed with a stable code when it can decide the bounded outcome; when
 * an injected component throws unexpectedly the invoker catches the unchecked failure and reports one bounded
 * {@link CampaignToolHandler#FAILURE_CODE} without echoing the message.
 *
 * <p>The handler is also where the protected operation really runs: it drives the accepted immutable catalog
 * and capability pack and returns the accepted typed artifact through {@link CampaignToolExecution}, so no
 * protected implementation can be reached outside an admitted call.
 */
@FunctionalInterface
public interface CampaignToolHandler {

    /** Executes one typed Tool request against the host run evidence. */
    CampaignToolExecution execute(CampaignToolRunContext run, CampaignToolRequest request);

    /** The stable code reported when a handler throws instead of failing closed. */
    String FAILURE_CODE = CampaignAssemblyErrorCodes.TOOL_EXECUTION_FAILED;
}