package com.company.campaign.builder.segment;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.query.sql.FxRateBinding;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;

import java.util.Objects;

/**
 * The exact host-supplied inputs of one development AI-to-SQL invocation.
 *
 * <p>Every trusted input is supplied by the host: the permitted Metadata snapshot, the resolution policy, the
 * registered-default source, the trusted execution context, the validation policy and the server-owned storage
 * catalog / SQL compile policy / optional FX binding. None of these is ever derived from model output.
 *
 * <p>It deliberately carries <em>no</em> authorization field. The single host authorization boundary is the
 * {@code AuthorizationService} injected into {@link DevelopmentAiToSqlService}; the request and the model
 * request have no field capable of supplying, overriding or weakening it.
 */
public record DevelopmentAiToSqlRequest(
        String utterance,
        String localeTag,
        MetadataSnapshot permittedSnapshot,
        ResolutionPolicy resolutionPolicy,
        RegisteredDefaultSource registeredDefaults,
        TrustedExecutionContext trustedContext,
        SegmentValidationPolicy validationPolicy,
        StorageCatalog storageCatalog,
        SqlCompilePolicy sqlCompilePolicy,
        FxRateBinding fxRateBinding
) {

    public DevelopmentAiToSqlRequest {
        utterance = Objects.requireNonNull(utterance, "utterance");
        localeTag = Objects.requireNonNull(localeTag, "localeTag");
        permittedSnapshot = Objects.requireNonNull(permittedSnapshot, "permittedSnapshot");
        resolutionPolicy = Objects.requireNonNull(resolutionPolicy, "resolutionPolicy");
        registeredDefaults = Objects.requireNonNull(registeredDefaults, "registeredDefaults");
        trustedContext = Objects.requireNonNull(trustedContext, "trustedContext");
        validationPolicy = Objects.requireNonNull(validationPolicy, "validationPolicy");
        storageCatalog = Objects.requireNonNull(storageCatalog, "storageCatalog");
        sqlCompilePolicy = Objects.requireNonNull(sqlCompilePolicy, "sqlCompilePolicy");
    }
}