package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.fixture.FxRateSnapshot;

/**
 * The exact host-owned T09 service objects one fixture is executed against.
 *
 * <p>They are supplied by the host because the server-owned mapping, DataScope binding and FX snapshot are
 * host artifacts, not corpus assets. The Evaluation Runner freezes their content hashes into the run contract
 * and then executes the genuine {@code FixtureExecutor} with these exact instances, so "the pinned fixture
 * services" and "the services actually used" cannot diverge.
 */
public record FixtureExecutionServices(
        FixtureMapping mapping,
        FixtureScopeBinding scopeBinding,
        FxRateSnapshot fxRateSnapshot
) {

    public FixtureExecutionServices {
        if (mapping == null || scopeBinding == null || fxRateSnapshot == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.HOST_SERVICE_MISSING,
                    "Fixture execution requires the exact mapping, scope binding and FX snapshot");
        }
    }
}