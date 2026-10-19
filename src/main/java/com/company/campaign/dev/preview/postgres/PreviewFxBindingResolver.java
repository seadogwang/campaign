package com.company.campaign.dev.preview.postgres;

import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.sql.FxRateBinding;

/**
 * The optional host-owned FX binding resolver of the PostgreSQL Preview adapter.
 *
 * <p>It resolves one immutable {@link FxRateBinding} for one genuine plan, or {@code null} when the host has
 * no exact binding for it. It never accepts SQL, a request value, a rate table name or a raw rate: the binding
 * it returns is the accepted T09B type, whose own canonical hash is bound into the compiled artifact.
 *
 * <p>A missing resolver and a required conversion fail closed at compilation, so an approximated rate can
 * never reach execution. A resolver that returns a binding the compiler cannot use fails the same way.
 */
@FunctionalInterface
public interface PreviewFxBindingResolver {

    /** The exact host-owned FX binding for one plan, or {@code null} when none applies. */
    FxRateBinding resolve(SemanticQueryPlan plan);
}