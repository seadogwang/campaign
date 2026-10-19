package com.company.campaign.evaluation.segment.contract;

/**
 * Bounded grammars shared by the evaluation assets.
 *
 * <p>Only grammars that are specific to evaluation assets live here. Identifiers, machine codes and
 * currencies reuse {@code com.company.campaign.contracts.ContractPatterns} so that a fixture id accepted
 * by a case is accepted by every other contract that carries one.
 *
 * <p>The path grammar is a shape check only: it forbids an absolute-looking first character, backslashes
 * and a missing {@code .json} suffix. Traversal, normalization and containment under the evaluation root
 * are enforced by the loader against the real filesystem, where they can be decided per platform.
 */
public final class EvaluationAssetPatterns {

    /** Repository-relative JSON asset path, such as {@code evaluation/segment/cases/case-001.json}. */
    public static final String REPOSITORY_RELATIVE_JSON = "^[A-Za-z0-9][A-Za-z0-9._/-]*\\.json$";

    private EvaluationAssetPatterns() {
    }
}