package com.company.campaign.preview;

/**
 * The client-requested safe presentation flags of one Preview call.
 *
 * <p>Both flags only ever <em>reduce</em> the response. {@code includeConditionStats} selects whether the
 * policy-bounded condition statistics are projected at all, and {@code includeSample} selects whether a
 * policy-bounded, separately authorized sample is requested. Neither flag can raise a server limit, select a
 * backend, name a DataScope or carry any trusted fact: the strict {@link PreviewRequestCodec} admits exactly
 * these two booleans and nothing else.
 *
 * <p>The codec policy is strict — both booleans are required explicitly — so an omitted flag is a missing
 * field and never a silently assumed value.
 */
public record PreviewPresentation(boolean includeSample, boolean includeConditionStats) {
}