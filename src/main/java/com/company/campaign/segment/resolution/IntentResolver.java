package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTextNormalizer;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
import com.company.campaign.metadata.domain.EventSelectorBinding;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.segment.intent.IntentAmbiguity;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentFilterScopeAmbiguity;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.IntentScopeFilter;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentValueKind;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentWindowAlternative;
import com.company.campaign.segment.intent.IntentWindowAmbiguity;
import com.company.campaign.segment.intent.RegisteredDefaultRevisionVerifier;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic, evidence-based Metadata candidate resolver.
 *
 * <p>It receives only explicit immutable inputs: the validated Intent, one already host-authorized and
 * permission-filtered {@link MetadataSnapshot}, one exact {@link ResolutionPolicy} and the injected
 * registered-default source. It never reads tenant, user or DataScope from the Intent, never queries an
 * unrestricted repository and cannot distinguish a missing definition from a permission-filtered one, so a
 * denied definition is never disclosed.
 *
 * <p>For every clause it compares each permitted definition against every populated intent slot, records
 * positive evidence and a single stable rejection reason. A fully compatible candidate is accepted when it is
 * the only compatible definition with exact identification evidence, when the model's sole stable-id hint and
 * the versioned concept policy independently identify that same permitted definition, or when the policy-mapped
 * candidate is uniquely grounded by the source span. Structural compatibility alone can never produce
 * {@code RESOLVED}: an ungrounded match asks for an explicit definition selection instead. Evidence tier,
 * Metadata kind and stable definition id order the output; the explanatory decimal score can never override an
 * incompatibility or select between semantically conflicting candidates. Attribute, Metric and Relation
 * candidates are all projected through one cast-safe view. It never constructs a Segment DSL.
 */
public final class IntentResolver {

    private static final Comparator<CandidateEvidence> CANDIDATE_ORDER =
            Comparator.comparingInt((CandidateEvidence candidate) -> candidate.tier().ordinal())
                    .thenComparingInt(candidate -> candidate.kind().ordinal())
                    .thenComparing(CandidateEvidence::definitionId);
    private static final Comparator<RejectedCandidate> REJECTED_ORDER =
            Comparator.comparingInt((RejectedCandidate candidate) -> candidate.kind().ordinal())
                    .thenComparing(RejectedCandidate::definitionId);

    /** Rejection reasons that require more user input rather than a hard "unsupported" verdict. */
    private static final Set<RejectionReason> NEEDS_INPUT_REASONS = EnumSet.of(
            RejectionReason.NULL_INCLUSION_AMBIGUOUS,
            RejectionReason.CURRENCY_MISSING,
            RejectionReason.NEGATIVE_EXAMPLE_CONFLICT,
            RejectionReason.SEMANTIC_POLICY_CONFLICT);

    private static final Set<ComparisonOperator> NULL_OPERATORS =
            EnumSet.of(ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL);
    private static final Set<ComparisonOperator> LIST_OPERATORS =
            EnumSet.of(ComparisonOperator.IN, ComparisonOperator.NOT_IN);
    private static final Set<ComparisonOperator> RANGE_OPERATORS =
            EnumSet.of(ComparisonOperator.BETWEEN);
    private static final Set<ComparisonOperator> NEGATIVE_OPERATORS =
            EnumSet.of(ComparisonOperator.NOT_EQUAL, ComparisonOperator.NOT_IN);

    private final MetadataSnapshot snapshot;
    private final ResolutionPolicy policy;
    private final RegisteredDefaultSource defaults;
    private final AudienceReferenceDirectoryContext referenceDirectory;

    public IntentResolver(
            MetadataSnapshot permittedSnapshot, ResolutionPolicy policy, RegisteredDefaultSource defaults) {
        this(permittedSnapshot, policy, defaults, null);
    }

    /**
     * Creates the one resolver, optionally with the host-owned reference-discovery context.
     *
     * <p>The context is the only way an Existing Audience Reference can be resolved: it is host-owned and is
     * never reachable from the Intent, a DSL document or a model payload. An Intent that asks for a reference
     * and no context fails closed, exactly like a reference-bearing document without a trusted validator
     * context, rather than degrading to an unchecked candidate.
     */
    public IntentResolver(
            MetadataSnapshot permittedSnapshot,
            ResolutionPolicy policy,
            RegisteredDefaultSource defaults,
            AudienceReferenceDirectoryContext referenceDirectory) {
        this.snapshot = Objects.requireNonNull(permittedSnapshot, "permittedSnapshot");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.referenceDirectory = referenceDirectory;
    }

    /** True when this resolver can resolve Existing Audience References. */
    public boolean resolvesAudienceReferences() {
        return referenceDirectory != null;
    }

    /** Resolves a validated Intent against the permitted snapshot, deterministically. */
    public ResolutionResult resolve(SegmentIntent intent) {
        return resolve(intent, java.util.Map.of());
    }

    /**
     * Resolves with an explicit per-clause selection applied.
     *
     * <p>This overload is internal implementation only and deliberately not public: the public
     * {@link #resolve(SegmentIntent)} is selection-free. A selection is the host applying a validated
     * clarification choice through {@link ClarificationChoiceService}, which first re-proves every current
     * pin and the exact option receipt; a caller cannot reach this overload to force a candidate without that
     * receipt. The selected definition is never taken on trust — an incompatible selection raises a stable
     * failure rather than being applied. The result is a new deterministic resolution, never a DSL or a
     * mutation.
     */
    ResolutionResult resolve(SegmentIntent intent, java.util.Map<String, String> selections) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(selections, "selections");
        ContentHash baseIntentHash = SegmentIntentHasher.hash(intent);
        ResolutionPins pins = new ResolutionPins(
                snapshot.snapshotId(),
                snapshot.snapshotVersion(),
                snapshot.contentHash(),
                policy.policyVersion(),
                policy.contentHash(),
                defaults.registryVersion(),
                defaults.registryHash());

        List<QuestionCode> globalQuestions = new ArrayList<>();
        for (IntentAmbiguity ambiguity : intent.ambiguities()) {
            QuestionCode code = QuestionCode.forAmbiguity(ambiguity);
            if (!globalQuestions.contains(code)) {
                globalQuestions.add(code);
            }
        }

        boolean unsupportedIntent = !intent.unsupportedRequirements().isEmpty();
        List<ClauseResolution> clauses = new ArrayList<>(intent.clauses().size());
        for (IntentClause clause : intent.clauses()) {
            if (unsupportedIntent) {
                clauses.add(unsupportedClause(clause, baseIntentHash, pins));
            } else {
                ClauseResolution base = resolveClause(
                        intent, clause, selections.get(clause.clauseId()), baseIntentHash, pins);
                clauses.add(resolveScopeFilters(clause, base, selections));
            }
        }

        // The reference half is resolved by its own deterministic step, through the host-owned directory. It is
        // deliberately not a clause: an audience is never a Metadata definition, and its accepted fact is an
        // immutable version pin rather than an accepted definition id.
        List<AudienceReferenceResolution> references = unsupportedIntent
                ? unsupportedReferences(intent)
                : resolveReferences(intent, selections);

        Disposition overall = overallDisposition(clauses, references, globalQuestions);
        Clarification clarification = overall == Disposition.NEEDS_INPUT
                ? buildClarification(intent, baseIntentHash, pins, clauses, references, globalQuestions)
                : null;
        return new ResolutionResult(overall, baseIntentHash, pins, clauses, clarification, List.of(), references);
    }

    private Disposition overallDisposition(
            List<ClauseResolution> clauses,
            List<AudienceReferenceResolution> references,
            List<QuestionCode> globalQuestions) {
        boolean anyUnsupported = false;
        boolean anyDenied = false;
        boolean allResolved = globalQuestions.isEmpty();
        for (ClauseResolution clause : clauses) {
            if (clause.disposition() == Disposition.UNSUPPORTED) {
                anyUnsupported = true;
            }
            if (clause.disposition() != Disposition.RESOLVED) {
                allResolved = false;
            }
        }
        for (AudienceReferenceResolution reference : references) {
            if (!reference.disposition().isResolved()) {
                allResolved = false;
            }
            if (reference.disposition() == Disposition.UNSUPPORTED) {
                anyUnsupported = true;
            }
            if (reference.disposition() == Disposition.DENIED) {
                anyDenied = true;
            }
        }
        if (anyUnsupported) {
            return Disposition.UNSUPPORTED;
        }
        if (anyDenied) {
            // A denial is strictly more specific than "needs input": asking the user to choose again would
            // invite them to answer a question the host has already refused.
            return Disposition.DENIED;
        }
        return allResolved ? Disposition.RESOLVED : Disposition.NEEDS_INPUT;
    }

    // --- audience-reference resolution ---------------------------------------------------------

    /**
     * Resolves every first-class audience reference of the Intent, in declaration order.
     *
     * <p>An Intent that carries a reference and no host directory context fails closed: the exact immutable
     * version can only come from the host, so there is nothing honest left to do.
     */
    private List<AudienceReferenceResolution> resolveReferences(
            SegmentIntent intent, java.util.Map<String, String> selections) {
        if (!intent.hasAudienceReferences()) {
            return List.of();
        }
        if (referenceDirectory == null) {
            throw new ResolutionException(
                    ResolutionErrorCodes.REFERENCE_DIRECTORY_MISSING,
                    "An Intent with an Existing Audience Reference requires the host discovery context");
        }
        AudienceReferenceResolver resolver = new AudienceReferenceResolver(referenceDirectory);
        List<AudienceReferenceResolution> references = new ArrayList<>(intent.audienceReferences().size());
        for (com.company.campaign.segment.intent.IntentAudienceReference reference : intent.audienceReferences()) {
            references.add(resolver.resolve(
                    reference.referenceId(),
                    reference.candidateTexts(),
                    spanText(reference.provenance()),
                    selections.get(reference.referenceId()),
                    selections.get(AudienceReferenceResolver.versionSelectionTarget(reference.referenceId()))));
        }
        return List.copyOf(references);
    }

    /** A wholly unsupported Intent cannot resolve a reference either: nothing is looked up for it. */
    private static List<AudienceReferenceResolution> unsupportedReferences(SegmentIntent intent) {
        List<AudienceReferenceResolution> references = new ArrayList<>(intent.audienceReferences().size());
        for (com.company.campaign.segment.intent.IntentAudienceReference reference : intent.audienceReferences()) {
            references.add(new AudienceReferenceResolution(
                    reference.referenceId(), Disposition.UNSUPPORTED, null, null, null, false, false,
                    List.of(), List.of(QuestionCode.AUDIENCE_REFERENCE_UNAVAILABLE), 0, null));
        }
        return List.copyOf(references);
    }

    // --- clause resolution ---------------------------------------------------------------------

    private ClauseResolution resolveClause(
            SegmentIntent intent, IntentClause clause, String selection,
            ContentHash intentHash, ResolutionPins pins) {
        List<CandidateEvidence> compatible = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        Set<RejectionReason> needsInputReasons = new LinkedHashSet<>();
        String spanText = spanText(clause);

        for (MetadataDefinition definition : permittedDefinitions(clause)) {
            DefinitionView view = DefinitionView.of(definition);
            List<EvidenceCode> evidence = new ArrayList<>();
            Optional<RejectionReason> rejection = evaluate(intent, clause, view, spanText, evidence);
            if (rejection.isPresent()) {
                RejectionReason reason = rejection.get();
                if (NEEDS_INPUT_REASONS.contains(reason)) {
                    needsInputReasons.add(reason);
                }
                rejected.add(new RejectedCandidate(definition.id(), definition.kind(), reason, evidence));
            } else {
                CandidateTier tier = tierFor(clause, view, spanText, evidence);
                compatible.add(new CandidateEvidence(
                        definition.id(), definition.kind(), tier, evidence, tier.weight()));
            }
        }
        if (compatible.size() > 1 && !clause.scopeFilters().isEmpty()) {
            List<CandidateEvidence> scoped = compatible.stream()
                    .filter(candidate -> supportsScopeFilters(candidate.definitionId(), clause.scopeFilters()))
                    .toList();
            if (!scoped.isEmpty()) {
                for (CandidateEvidence candidate : compatible) {
                    if (!scoped.contains(candidate)) {
                        rejected.add(new RejectedCandidate(
                                candidate.definitionId(), candidate.kind(),
                                RejectionReason.SCOPE_FILTER_UNSUPPORTED, candidate.evidence()));
                    }
                }
                compatible = new ArrayList<>(scoped);
            }
        }
        compatible.sort(CANDIDATE_ORDER);
        rejected.sort(REJECTED_ORDER);

        if (selection != null) {
            for (CandidateEvidence candidate : compatible) {
                if (candidate.definitionId().equals(selection)) {
                    return new ClauseResolution(
                            clause.clauseId(), intentHash, pins, candidate.definitionId(), candidate.kind(),
                            compatible, rejected, Disposition.RESOLVED, List.of());
                }
            }
            throw new ResolutionException(
                    ResolutionErrorCodes.OPTION_INCOMPATIBLE,
                    "The selected clarification option is no longer compatible");
        }

        List<QuestionCode> questions = new ArrayList<>();
        for (RejectionReason reason : needsInputReasons) {
            questions.add(questionFor(reason));
        }
        CandidateEvidence consensusCandidate =
                consensusCandidate(clause.concept(), clause.candidateHints(), compatible);
        CandidateEvidence groundedPolicyCandidate = consensusCandidate == null
                ? groundedPolicyCandidate(clause, spanText, compatible)
                : null;
        CandidateEvidence automaticallyAccepted = consensusCandidate != null
                ? consensusCandidate
                : groundedPolicyCandidate;
        if (automaticallyAccepted != null) {
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins,
                    automaticallyAccepted.definitionId(), automaticallyAccepted.kind(),
                    compatible, rejected, Disposition.RESOLVED, List.of());
        }
        if (compatible.size() > 1) {
            addOnce(questions, QuestionCode.DISAMBIGUATE_DEFINITION);
        }

        if (compatible.size() == 1 && questions.isEmpty()
                && compatible.get(0).tier().isIdentificationEvidence()) {
            CandidateEvidence accepted = compatible.get(0);
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins, accepted.definitionId(), accepted.kind(),
                    compatible, rejected, Disposition.RESOLVED, List.of());
        }
        if (compatible.size() == 1) {
            // A single compatible candidate still needs input when a slot is genuinely ambiguous, or when it
            // is only structurally compatible. Structural compatibility alone can never produce RESOLVED, so
            // an ungrounded sole match asks for an explicit definition selection instead.
            if (questions.isEmpty()) {
                questions.add(QuestionCode.SELECT_DEFINITION);
            }
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins, null, null,
                    compatible, rejected, Disposition.NEEDS_INPUT, questions);
        }
        if (compatible.size() > 1) {
            addOnce(questions, QuestionCode.DISAMBIGUATE_DEFINITION);
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins, null, null,
                    compatible, rejected, Disposition.NEEDS_INPUT, questions);
        }
        if (!needsInputReasons.isEmpty()) {
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins, null, null,
                    compatible, rejected, Disposition.NEEDS_INPUT, questions);
        }
        if (rejected.isEmpty()) {
            // No permitted definition was even comparable: absent and denied are indistinguishable here.
            questions.add(QuestionCode.SELECT_DEFINITION);
            return new ClauseResolution(
                    clause.clauseId(), intentHash, pins, null, null,
                    compatible, rejected, Disposition.NEEDS_INPUT, questions);
        }
        return new ClauseResolution(
                clause.clauseId(), intentHash, pins, null, null,
                compatible, rejected, Disposition.UNSUPPORTED, List.of());
    }

    private ClauseResolution unsupportedClause(
            IntentClause clause, ContentHash intentHash, ResolutionPins pins) {
        return new ClauseResolution(
                clause.clauseId(), intentHash, pins, null, null,
                List.of(), List.of(), Disposition.UNSUPPORTED, List.of());
    }

    private ClauseResolution resolveScopeFilters(
            IntentClause clause,
            ClauseResolution base,
            java.util.Map<String, String> selections) {
        List<IntentScopeFilter> selectorFilters = selectorFilters(clause.window());
        if (clause.scopeFilters().isEmpty() && selectorFilters.isEmpty() || !base.disposition().isResolved()) {
            return base;
        }
        if (base.acceptedKind() != MetadataKind.METRIC) {
            return withScopeFailure(base, List.of(), Disposition.UNSUPPORTED, List.of());
        }
        MetricDefinition metric = snapshot.metrics().stream()
                .filter(candidate -> candidate.id().equals(base.accepted()))
                .findFirst()
                .orElse(null);
        if (metric == null) {
            return withScopeFailure(base, List.of(), Disposition.UNSUPPORTED, List.of());
        }

        EventSelectorBinding selectorBinding = null;
        if (clause.window() instanceof IntentWindow.SelectedEvent selected) {
            selectorBinding = selectorBinding(selected.selectorId(), metric.relationId());
        } else if (clause.window() instanceof IntentWindow.RelativeToEvent relative) {
            selectorBinding = selectorBinding(relative.selectorId(), metric.relationId());
        }
        if (!selectorFilters.isEmpty() && selectorBinding == null) {
            return withScopeFailure(base, List.of(), Disposition.UNSUPPORTED, List.of());
        }

        List<ScopeFilterResolution> filters = new ArrayList<>(
                clause.scopeFilters().size() + selectorFilters.size());
        Disposition disposition = Disposition.RESOLVED;
        List<QuestionCode> questions = new ArrayList<>();
        for (IntentScopeFilter filter : clause.scopeFilters()) {
            ScopeFilterResolution resolved = resolveScopeFilter(
                    filter, metric.scopeFilterDimensions(),
                    selections.get(scopeSelectionKey(clause.clauseId(), filter.filterId())));
            filters.add(resolved);
            if (resolved.disposition() == Disposition.UNSUPPORTED) {
                disposition = Disposition.UNSUPPORTED;
            } else if (!resolved.disposition().isResolved() && disposition != Disposition.UNSUPPORTED) {
                disposition = Disposition.NEEDS_INPUT;
            }
            for (QuestionCode question : resolved.questionCodes()) {
                addOnce(questions, question);
            }
        }
        for (IntentScopeFilter filter : selectorFilters) {
            ScopeFilterResolution resolved = resolveScopeFilter(
                    filter, selectorBinding.scopeFilterDimensions(),
                    selections.get(scopeSelectionKey(clause.clauseId(), filter.filterId())));
            filters.add(resolved);
            if (resolved.disposition() == Disposition.UNSUPPORTED) {
                disposition = Disposition.UNSUPPORTED;
            } else if (!resolved.disposition().isResolved() && disposition != Disposition.UNSUPPORTED) {
                disposition = Disposition.NEEDS_INPUT;
            }
            for (QuestionCode question : resolved.questionCodes()) {
                addOnce(questions, question);
            }
        }
        if (disposition.isResolved()) {
            return new ClauseResolution(
                    base.clauseId(), base.intentHash(), base.pins(), base.accepted(), base.acceptedKind(),
                    base.candidates(), base.rejected(), filters, Disposition.RESOLVED, List.of());
        }
        return withScopeFailure(base, filters, disposition, questions);
    }

    private ClauseResolution withScopeFailure(
            ClauseResolution base,
            List<ScopeFilterResolution> filters,
            Disposition disposition,
            List<QuestionCode> questions) {
        return new ClauseResolution(
                base.clauseId(), base.intentHash(), base.pins(), null, null,
                base.candidates(), base.rejected(), filters, disposition, questions);
    }

    private ScopeFilterResolution resolveScopeFilter(
            IntentScopeFilter filter,
            List<MetricFilterDimension> dimensions,
            String selection) {
        List<CandidateEvidence> compatible = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        String spanText = spanText(filter.provenance());
        for (MetricFilterDimension dimension : dimensions) {
            AttributeDefinition attribute = snapshot.attributes().stream()
                    .filter(candidate -> candidate.id().equals(dimension.attributeId()))
                    .findFirst()
                    .orElse(null);
            if (attribute == null) {
                continue;
            }
            List<EvidenceCode> evidence = new ArrayList<>();
            RejectionReason rejection = null;
            if (!dimension.allowedOperators().contains(filter.operator())) {
                rejection = RejectionReason.OPERATOR_NOT_ALLOWED;
            } else if (filter.value() != null && !dataTypeAccepts(attribute.dataType(), filter.value())) {
                rejection = RejectionReason.VALUE_TYPE_INCOMPATIBLE;
            } else {
                evidence.add(EvidenceCode.OPERATOR_COMPATIBLE);
                evidence.add(EvidenceCode.VALUE_TYPE_COMPATIBLE);
                evidence.add(EvidenceCode.VALUE_SHAPE_COMPATIBLE);
            }
            if (rejection != null) {
                rejected.add(new RejectedCandidate(attribute.id(), MetadataKind.ATTRIBUTE, rejection, evidence));
                continue;
            }
            CandidateTier tier = tierFor(filter, attribute, spanText, evidence);
            compatible.add(new CandidateEvidence(
                    attribute.id(), MetadataKind.ATTRIBUTE, tier, evidence, tier.weight()));
        }
        compatible.sort(CANDIDATE_ORDER);
        List<CandidateEvidence> narrowed = narrowScopeCandidates(compatible, dimensions, filter, spanText);
        if (narrowed.size() < compatible.size()) {
            Set<String> retained = new LinkedHashSet<>();
            narrowed.forEach(candidate -> retained.add(candidate.definitionId()));
            for (CandidateEvidence candidate : compatible) {
                if (!retained.contains(candidate.definitionId())) {
                    rejected.add(new RejectedCandidate(
                            candidate.definitionId(), candidate.kind(),
                            RejectionReason.FILTER_SEMANTIC_MISMATCH, candidate.evidence()));
                }
            }
            compatible = new ArrayList<>(narrowed);
        } else {
            compatible = new ArrayList<>(narrowed);
        }
        compatible.sort(CANDIDATE_ORDER);
        rejected.sort(REJECTED_ORDER);
        if (selection != null) {
            for (CandidateEvidence candidate : compatible) {
                if (candidate.definitionId().equals(selection)) {
                    return new ScopeFilterResolution(
                            filter.filterId(), candidate.definitionId(), compatible, rejected,
                            Disposition.RESOLVED, List.of());
                }
            }
            throw new ResolutionException(
                    ResolutionErrorCodes.OPTION_INCOMPATIBLE,
                    "The selected scope-filter clarification option is no longer compatible");
        }
        if (compatible.size() == 1 && compatible.get(0).tier().isIdentificationEvidence()) {
            return new ScopeFilterResolution(
                    filter.filterId(), compatible.get(0).definitionId(), compatible, rejected,
                    Disposition.RESOLVED, List.of());
        }
        CandidateEvidence consensus = consensusCandidate(
                filter.concept(), filter.candidateHints(), compatible);
        AttributeDefinition consensusAttribute = consensus == null ? null : snapshot.attributes().stream()
                .filter(attribute -> attribute.id().equals(consensus.definitionId()))
                .findFirst()
                .orElse(null);
        if (filter.domain() == FilterDomain.UNSPECIFIED
                && filter.identifierKind() == FilterIdentifierKind.UNSPECIFIED
                && consensus != null && consensusAttribute != null
                && semanticLabelGrounded(consensusAttribute, spanText)) {
            return new ScopeFilterResolution(
                    filter.filterId(), consensus.definitionId(), compatible, rejected,
                    Disposition.RESOLVED, List.of());
        }
        if (!compatible.isEmpty()) {
            QuestionCode question = compatible.size() > 1
                    ? QuestionCode.DISAMBIGUATE_DEFINITION
                    : QuestionCode.SELECT_DEFINITION;
            return new ScopeFilterResolution(
                    filter.filterId(), null, compatible, rejected, Disposition.NEEDS_INPUT, List.of(question));
        }
        return new ScopeFilterResolution(
                filter.filterId(), null, compatible, rejected, Disposition.UNSUPPORTED, List.of());
    }

    private List<CandidateEvidence> narrowScopeCandidates(
            List<CandidateEvidence> candidates,
            List<MetricFilterDimension> dimensions,
            IntentScopeFilter filter,
            String spanText) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<CandidateEvidence> matches = candidates;
        if (filter.domain() != FilterDomain.UNSPECIFIED
                || filter.identifierKind() != FilterIdentifierKind.UNSPECIFIED) {
            boolean domainOnly = filter.domain() != FilterDomain.UNSPECIFIED
                    && filter.identifierKind() == FilterIdentifierKind.UNSPECIFIED;
            boolean domainGrounded = domainOnly && candidates.stream()
                    .filter(candidate -> filterDimension(dimensions, candidate.definitionId()).domain()
                            == filter.domain())
                    .map(candidate -> snapshot.attributes().stream()
                            .filter(value -> value.id().equals(candidate.definitionId()))
                            .findFirst().orElse(null))
                    .anyMatch(attribute -> attribute != null && semanticLabelGrounded(attribute, spanText));
            matches = new ArrayList<>();
            for (CandidateEvidence candidate : candidates) {
                MetricFilterDimension dimension = filterDimension(dimensions, candidate.definitionId());
                if (filter.domain() != FilterDomain.UNSPECIFIED && dimension.domain() != filter.domain()) {
                    continue;
                }
                if (filter.identifierKind() != FilterIdentifierKind.UNSPECIFIED
                        && dimension.identifierKind() != filter.identifierKind()) {
                    continue;
                }
                if (!domainOnly) {
                    AttributeDefinition attribute = snapshot.attributes().stream()
                            .filter(value -> value.id().equals(candidate.definitionId()))
                            .findFirst().orElse(null);
                    if (attribute == null || !semanticLabelGrounded(attribute, spanText)) {
                        continue;
                    }
                } else if (!domainGrounded) {
                    continue;
                }
                matches.add(withFilterSemanticsEvidence(candidate, filter));
            }
            if (matches.isEmpty()) {
                return List.copyOf(candidates);
            }
        }
        List<CandidateEvidence> enumMatches = matches.stream()
                .filter(candidate -> enumValueMatches(candidate.definitionId(), filter.value()))
                .map(IntentResolver::withEnumValueEvidence)
                .toList();
        return enumMatches.isEmpty() ? List.copyOf(matches) : enumMatches;
    }

    private static boolean semanticLabelGrounded(AttributeDefinition attribute, String spanText) {
        if (spanText == null || spanText.isBlank()) {
            return false;
        }
        String normalized = MetadataTextNormalizer.normalize(spanText);
        if (normalized.contains(MetadataTextNormalizer.normalize(attribute.displayName()))) {
            return true;
        }
        return attribute.aliases().stream()
                .map(MetadataTextNormalizer::normalize)
                .anyMatch(normalized::contains);
    }

    private boolean enumValueMatches(String attributeId, IntentValue value) {
        AttributeDefinition attribute = snapshot.attributes().stream()
                .filter(candidate -> candidate.id().equals(attributeId))
                .findFirst()
                .orElse(null);
        if (attribute == null || (attribute.dataType() != SemanticDataType.ENUM
                && attribute.dataType() != SemanticDataType.ORDERED_ENUM)) {
            return false;
        }
        List<IntentValue.Scalar> values;
        if (value instanceof IntentValue.EnumValue enumValue) {
            values = List.of(enumValue);
        } else if (value instanceof IntentValue.ListValue listValue
                && listValue.items().stream().allMatch(IntentValue.EnumValue.class::isInstance)) {
            values = listValue.items();
        } else {
            return false;
        }
        return values.stream().allMatch(item -> {
            String raw = MetadataTextNormalizer.normalize(((IntentValue.EnumValue) item).raw());
            return attribute.enumValues().stream().anyMatch(enumValue ->
                    MetadataTextNormalizer.normalize(enumValue.code()).equals(raw)
                            || enumValue.aliases().stream().anyMatch(alias ->
                            MetadataTextNormalizer.normalize(alias).equals(raw)));
        });
    }

    private static CandidateEvidence withEnumValueEvidence(CandidateEvidence candidate) {
        List<EvidenceCode> evidence = new ArrayList<>(candidate.evidence());
        if (!evidence.contains(EvidenceCode.ENUM_VALUE_MATCH)) {
            evidence.add(EvidenceCode.ENUM_VALUE_MATCH);
        }
        CandidateTier tier = stronger(candidate.tier(), CandidateTier.FILTER_SEMANTICS_MATCH);
        return new CandidateEvidence(candidate.definitionId(), candidate.kind(), tier, evidence, tier.weight());
    }

    private static CandidateEvidence withFilterSemanticsEvidence(
            CandidateEvidence candidate,
            IntentScopeFilter filter) {
        List<EvidenceCode> evidence = new ArrayList<>(candidate.evidence());
        if (filter.domain() != FilterDomain.UNSPECIFIED
                && !evidence.contains(EvidenceCode.FILTER_DOMAIN_MATCH)) {
            evidence.add(EvidenceCode.FILTER_DOMAIN_MATCH);
        }
        if (filter.identifierKind() != FilterIdentifierKind.UNSPECIFIED
                && !evidence.contains(EvidenceCode.FILTER_IDENTIFIER_KIND_MATCH)) {
            evidence.add(EvidenceCode.FILTER_IDENTIFIER_KIND_MATCH);
        }
        CandidateTier tier = stronger(candidate.tier(), CandidateTier.FILTER_SEMANTICS_MATCH);
        return new CandidateEvidence(candidate.definitionId(), candidate.kind(), tier, evidence, tier.weight());
    }

    private static MetricFilterDimension filterDimension(
            List<MetricFilterDimension> dimensions, String attributeId) {
        return dimensions.stream()
                .filter(dimension -> dimension.attributeId().equals(attributeId))
                .findFirst()
                .orElseThrow(() -> new ResolutionException(
                        ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                        "A scope-filter candidate has no Metric filter dimension"));
    }

    private static List<IntentScopeFilter> selectorFilters(IntentWindow window) {
        if (window instanceof IntentWindow.SelectedEvent selected) {
            return selected.selectorScopeFilters();
        }
        if (window instanceof IntentWindow.RelativeToEvent relative) {
            return relative.selectorScopeFilters();
        }
        return List.of();
    }

    private EventSelectorBinding selectorBinding(String selectorId, String relationId) {
        EventSelectorDefinition selector = snapshot.eventSelectors().stream()
                .filter(candidate -> candidate.id().equals(selectorId))
                .findFirst()
                .orElse(null);
        return selector == null ? null : selector.bindingFor(relationId);
    }

    private CandidateTier tierFor(
            IntentScopeFilter filter,
            AttributeDefinition attribute,
            String spanText,
            List<EvidenceCode> evidence) {
        CandidateTier best = CandidateTier.STRUCTURAL_MATCH;
        if (filter.candidateHints().contains(attribute.id())) {
            evidence.add(EvidenceCode.EXACT_STABLE_ID_HINT);
            best = stronger(best, CandidateTier.EXACT_STABLE_ID_HINT);
        }
        String mapped = policy.conceptDefinitionId(filter.concept());
        if (mapped != null && mapped.equals(attribute.id())) {
            evidence.add(EvidenceCode.CONCEPT_POLICY_MATCH);
            best = stronger(best, CandidateTier.EXACT_CONCEPT_POLICY_MATCH);
        }
        String key = spanText == null ? "" : MetadataTextNormalizer.normalize(spanText);
        if (MetadataTextNormalizer.normalize(attribute.displayName()).equals(key)) {
            evidence.add(EvidenceCode.EXACT_DISPLAY_MATCH);
            best = stronger(best, CandidateTier.EXACT_DISPLAY_MATCH);
        }
        for (String alias : attribute.aliases()) {
            if (MetadataTextNormalizer.normalize(alias).equals(key)) {
                evidence.add(EvidenceCode.EXACT_ALIAS_MATCH);
                best = stronger(best, CandidateTier.EXACT_ALIAS_MATCH);
                break;
            }
        }
        for (String example : attribute.positiveExamples()) {
            if (MetadataTextNormalizer.normalize(example).equals(key)) {
                evidence.add(EvidenceCode.POSITIVE_EXAMPLE_MATCH);
                best = stronger(best, CandidateTier.POSITIVE_EXAMPLE_MATCH);
                break;
            }
        }
        return best;
    }

    private List<MetadataDefinition> permittedDefinitions(IntentClause clause) {
        List<MetadataDefinition> definitions = new ArrayList<>();
        if (clauseAllowsKind(clause.definitionKind(), MetadataKind.ATTRIBUTE)) {
            definitions.addAll(snapshot.attributes());
        }
        if (clauseAllowsKind(clause.definitionKind(), MetadataKind.METRIC)) {
            definitions.addAll(snapshot.metrics());
        }
        // Relations are offered only when the clause explicitly requests the RELATION kind: a clause without a
        // kind means "attribute or metric", so a relation is never silently substituted for one of those.
        if (clause.definitionKind() == MetadataKind.RELATION) {
            definitions.addAll(snapshot.relations());
        }
        return definitions;
    }

    private static boolean clauseAllowsKind(MetadataKind requested, MetadataKind candidate) {
        return requested == null || requested == candidate;
    }

    // --- per-definition evaluation -------------------------------------------------------------

    private Optional<RejectionReason> evaluate(
            SegmentIntent intent, IntentClause clause, DefinitionView view, String spanText,
            List<EvidenceCode> evidence) {

        if (clause.definitionKind() != null && clause.definitionKind() != view.kind()) {
            return Optional.of(RejectionReason.KIND_MISMATCH);
        }
        if (view.subject() != intent.subject()) {
            return Optional.of(RejectionReason.SUBJECT_MISMATCH);
        }
        Optional<RejectionReason> conflict = semanticConflict(clause, view, spanText, evidence);
        if (conflict.isPresent()) {
            return conflict;
        }
        if (view.kind() == MetadataKind.RELATION) {
            // A relation carries no operator/value/window/currency/aggregation capability: a clause that
            // populates one is rejected explicitly rather than being silently ignored.
            Optional<RejectionReason> relation = relationSlotsCheck(clause, view, evidence);
            if (relation.isPresent()) {
                return relation;
            }
            evidence.add(EvidenceCode.KIND_MATCH);
            evidence.add(EvidenceCode.SUBJECT_MATCH);
            return Optional.empty();
        }
        Optional<RejectionReason> operator = operatorCheck(clause, view, evidence);
        if (operator.isPresent()) {
            return operator;
        }
        Optional<RejectionReason> value = valueCheck(clause, view, evidence);
        if (value.isPresent()) {
            return value;
        }
        Optional<RejectionReason> window = windowCheck(clause, view, evidence);
        if (window.isPresent()) {
            return window;
        }
        Optional<RejectionReason> currency = currencyCheck(clause, view, evidence);
        if (currency.isPresent()) {
            return currency;
        }
        Optional<RejectionReason> aggregation = aggregationCheck(clause, view, evidence);
        if (aggregation.isPresent()) {
            return aggregation;
        }
        Optional<RejectionReason> relation = relationCheck(clause, view, evidence);
        if (relation.isPresent()) {
            return relation;
        }
        Optional<RejectionReason> nullSemantics = nullSemanticsCheck(clause, view);
        if (nullSemantics.isPresent()) {
            return nullSemantics;
        }
        evidence.add(EvidenceCode.KIND_MATCH);
        evidence.add(EvidenceCode.SUBJECT_MATCH);
        return Optional.empty();
    }

    /**
     * A negative-example conflict is a plain rejection; when the clause also names the definition as a
     * stable-id hint, the conflict is between the model's own hint and the definition's declared policy, so
     * it is reported as a semantic-policy conflict instead. Both are {@code NEEDS_INPUT} causes.
     */
    private Optional<RejectionReason> semanticConflict(
            IntentClause clause, DefinitionView view, String spanText, List<EvidenceCode> evidence) {
        if (spanText == null || spanText.isEmpty()) {
            return Optional.empty();
        }
        String key = MetadataTextNormalizer.normalize(spanText);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        for (String negative : view.negativeExamples()) {
            if (MetadataTextNormalizer.normalize(negative).equals(key)) {
                boolean hinted = clause.candidateHints().contains(view.definition().id());
                evidence.add(hinted ? EvidenceCode.SEMANTIC_POLICY_CONFLICT : EvidenceCode.NEGATIVE_EXAMPLE_CONFLICT);
                return Optional.of(hinted
                        ? RejectionReason.SEMANTIC_POLICY_CONFLICT
                        : RejectionReason.NEGATIVE_EXAMPLE_CONFLICT);
            }
        }
        return Optional.empty();
    }

    private Optional<RejectionReason> operatorCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        if (clause.operator() == null) {
            return Optional.empty();
        }
        if (!view.allowedOperators().contains(clause.operator())) {
            return Optional.of(RejectionReason.OPERATOR_NOT_ALLOWED);
        }
        evidence.add(EvidenceCode.OPERATOR_COMPATIBLE);
        return Optional.empty();
    }

    private Optional<RejectionReason> valueCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        ComparisonOperator operator = clause.operator();
        IntentValue value = clause.value();
        if (operator != null && NULL_OPERATORS.contains(operator)) {
            return value == null
                    ? Optional.empty()
                    : Optional.of(RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
        }
        if (value == null) {
            return Optional.empty();
        }
        if (operator != null) {
            if (LIST_OPERATORS.contains(operator) && value.kind() != IntentValueKind.LIST) {
                return Optional.of(RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
            }
            if (RANGE_OPERATORS.contains(operator) && value.kind() != IntentValueKind.RANGE) {
                return Optional.of(RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
            }
            if (!LIST_OPERATORS.contains(operator) && !RANGE_OPERATORS.contains(operator)
                    && !value.kind().isScalar()) {
                return Optional.of(RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
            }
        }
        if (!dataTypeAccepts(view.dataType(), value)) {
            return Optional.of(RejectionReason.VALUE_TYPE_INCOMPATIBLE);
        }
        if (view.definition() instanceof AttributeDefinition attribute
                && (attribute.dataType() == SemanticDataType.ENUM
                    || attribute.dataType() == SemanticDataType.ORDERED_ENUM)
                && !enumDefinitionAccepts(attribute, value)) {
            return Optional.of(RejectionReason.VALUE_TYPE_INCOMPATIBLE);
        }
        evidence.add(EvidenceCode.VALUE_TYPE_COMPATIBLE);
        evidence.add(EvidenceCode.VALUE_SHAPE_COMPATIBLE);
        return Optional.empty();
    }

    private boolean dataTypeAccepts(SemanticDataType dataType, IntentValue value) {
        if (value instanceof IntentValue.ListValue list) {
            for (IntentValue.Scalar item : list.items()) {
                if (!scalarAccepts(dataType, item)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof IntentValue.RangeValue range) {
            return scalarAccepts(dataType, range.lower()) && scalarAccepts(dataType, range.upper());
        }
        return scalarAccepts(dataType, (IntentValue.Scalar) value);
    }

    private static boolean enumDefinitionAccepts(AttributeDefinition attribute, IntentValue value) {
        List<IntentValue.Scalar> values;
        if (value instanceof IntentValue.EnumValue enumValue) {
            values = List.of(enumValue);
        } else if (value instanceof IntentValue.ListValue listValue
                && listValue.items().stream().allMatch(IntentValue.EnumValue.class::isInstance)) {
            values = listValue.items();
        } else {
            return false;
        }
        return values.stream().allMatch(item -> {
            String raw = MetadataTextNormalizer.normalize(((IntentValue.EnumValue) item).raw());
            return attribute.enumValues().stream().anyMatch(enumValue ->
                    MetadataTextNormalizer.normalize(enumValue.code()).equals(raw)
                            || enumValue.aliases().stream().anyMatch(alias ->
                            MetadataTextNormalizer.normalize(alias).equals(raw)));
        });
    }

    private boolean scalarAccepts(SemanticDataType dataType, IntentValue.Scalar scalar) {
        return switch (dataType) {
            case STRING -> scalar.kind() == IntentValueKind.STRING;
            case BOOLEAN -> scalar.kind() == IntentValueKind.BOOLEAN;
            case INTEGER -> scalar.kind() == IntentValueKind.INTEGER;
            case DECIMAL -> scalar.kind() == IntentValueKind.DECIMAL
                    || scalar.kind() == IntentValueKind.INTEGER;
            case DATE -> scalar.kind() == IntentValueKind.DATE;
            case TIMESTAMP -> scalar.kind() == IntentValueKind.TIMESTAMP;
            case ENUM, ORDERED_ENUM -> scalar.kind() == IntentValueKind.ENUM;
            case MONEY -> scalar.kind() == IntentValueKind.MONEY;
        };
    }

    private Optional<RejectionReason> windowCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        IntentWindow window = clause.window();
        if (window == null) {
            return Optional.empty();
        }
        if (view.kind() != MetadataKind.METRIC || !view.supportedWindows().contains(window.kind())) {
            return Optional.of(RejectionReason.WINDOW_UNSUPPORTED);
        }
        if (window instanceof IntentWindow.RelativeToAttribute relative) {
            AttributeDefinition anchor = snapshot.attributes().stream()
                    .filter(candidate -> candidate.id().equals(relative.anchorDefinitionId()))
                    .findFirst()
                    .orElse(null);
            if (anchor == null || anchor.subject() != view.subject()
                    || (anchor.dataType() != SemanticDataType.DATE
                    && anchor.dataType() != SemanticDataType.TIMESTAMP)) {
                return Optional.of(RejectionReason.WINDOW_UNSUPPORTED);
            }
        }
        if (window instanceof IntentWindow.SelectedEvent selected
                && selectorBinding(selected.selectorId(), view.relationId()) == null) {
            return Optional.of(RejectionReason.WINDOW_UNSUPPORTED);
        }
        if (window instanceof IntentWindow.RelativeToEvent relative
                && selectorBinding(relative.selectorId(), view.relationId()) == null) {
            return Optional.of(RejectionReason.WINDOW_UNSUPPORTED);
        }
        evidence.add(EvidenceCode.WINDOW_COMPATIBLE);
        return Optional.empty();
    }

    private Optional<RejectionReason> currencyCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        boolean money = view.dataType() == SemanticDataType.MONEY;
        IntentCurrency currency = clause.currency();
        if (!money) {
            return currency == null
                    ? Optional.empty()
                    : Optional.of(RejectionReason.CURRENCY_INCOMPATIBLE);
        }
        if (currency instanceof IntentCurrency.Explicit explicit) {
            evidence.add(EvidenceCode.CURRENCY_COMPATIBLE);
            return Optional.empty();
        }
        if (currency instanceof IntentCurrency.RegisteredDefault registered) {
            RegisteredDefaultRevisionVerifier.resolveCurrency(defaults, registered.defaultId());
            evidence.add(EvidenceCode.CURRENCY_COMPATIBLE);
            return Optional.empty();
        }
        if (policy.reportingCurrencyDefaultId() != null) {
            RegisteredDefaultRevisionVerifier.resolveCurrency(defaults, policy.reportingCurrencyDefaultId());
            evidence.add(EvidenceCode.CURRENCY_COMPATIBLE);
            return Optional.empty();
        }
        return Optional.of(RejectionReason.CURRENCY_MISSING);
    }

    private Optional<RejectionReason> aggregationCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        AggregateFunction aggregation = clause.aggregation();
        if (aggregation == null) {
            return Optional.empty();
        }
        if (view.kind() != MetadataKind.METRIC || view.aggregation() != aggregation) {
            return Optional.of(RejectionReason.AGGREGATION_MISMATCH);
        }
        evidence.add(EvidenceCode.AGGREGATION_COMPATIBLE);
        return Optional.empty();
    }

    private boolean supportsScopeFilters(String definitionId, List<IntentScopeFilter> filters) {
        MetricDefinition metric = snapshot.metrics().stream()
                .filter(candidate -> candidate.id().equals(definitionId))
                .findFirst()
                .orElse(null);
        if (metric == null) {
            return false;
        }
        for (IntentScopeFilter filter : filters) {
            List<MetricFilterDimension> compatible = metric.scopeFilterDimensions().stream().filter(dimension -> {
                if (!dimension.allowedOperators().contains(filter.operator())) {
                    return false;
                }
                AttributeDefinition attribute = snapshot.attributes().stream()
                        .filter(candidate -> candidate.id().equals(dimension.attributeId()))
                        .findFirst()
                        .orElse(null);
                return attribute != null
                        && (filter.value() == null || dataTypeAccepts(attribute.dataType(), filter.value()));
            }).filter(dimension -> filter.domain() == FilterDomain.UNSPECIFIED
                    || dimension.domain() == filter.domain())
                    .filter(dimension -> filter.identifierKind() == FilterIdentifierKind.UNSPECIFIED
                            || dimension.identifierKind() == filter.identifierKind())
                    .toList();
            if (compatible.isEmpty()) {
                return false;
            }
            String mappedId = policy.conceptDefinitionId(filter.concept());
            boolean hasMappedDimension = mappedId != null && compatible.stream()
                    .anyMatch(dimension -> dimension.attributeId().equals(mappedId));
            boolean hasHintedDimension = compatible.stream()
                    .anyMatch(dimension -> filter.candidateHints().contains(dimension.attributeId()));
            boolean hasEnumValueDimension = compatible.stream()
                    .anyMatch(dimension -> enumValueMatches(dimension.attributeId(), filter.value()));
            if ((mappedId != null || !filter.candidateHints().isEmpty())
                    && !hasMappedDimension && !hasHintedDimension && !hasEnumValueDimension) {
                return false;
            }
        }
        return true;
    }

    private Optional<RejectionReason> relationCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        RelationScope scope = clause.relationScope();
        if (scope == null) {
            return Optional.empty();
        }
        if (view.kind() != MetadataKind.METRIC || view.relationId() == null) {
            return Optional.of(RejectionReason.RELATION_MISMATCH);
        }
        RelationDefinition relation = snapshot.relations().stream()
                .filter(candidate -> candidate.id().equals(view.relationId()))
                .findFirst()
                .orElse(null);
        if (relation == null || !relation.scope().equals(scope)) {
            return Optional.of(RejectionReason.RELATION_MISMATCH);
        }
        evidence.add(EvidenceCode.RELATION_COMPATIBLE);
        return Optional.empty();
    }

    /**
     * A Relation candidate accepts only relation-appropriate slots. An operator, value, window, currency or
     * aggregation slot is a capability a relation simply does not have, so each is rejected with its own
     * stable reason. A populated relation-scope intent must equal the relation's declared scope exactly.
     */
    private Optional<RejectionReason> relationSlotsCheck(
            IntentClause clause, DefinitionView view, List<EvidenceCode> evidence) {
        if (clause.operator() != null) {
            return Optional.of(RejectionReason.OPERATOR_NOT_ALLOWED);
        }
        if (clause.value() != null) {
            return Optional.of(RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
        }
        if (clause.window() != null) {
            return Optional.of(RejectionReason.WINDOW_UNSUPPORTED);
        }
        if (clause.currency() != null) {
            return Optional.of(RejectionReason.CURRENCY_INCOMPATIBLE);
        }
        if (clause.aggregation() != null) {
            return Optional.of(RejectionReason.AGGREGATION_MISMATCH);
        }
        RelationScope scope = clause.relationScope();
        if (scope != null) {
            if (!scope.equals(view.relationScope())) {
                return Optional.of(RejectionReason.RELATION_MISMATCH);
            }
            evidence.add(EvidenceCode.RELATION_COMPATIBLE);
        }
        return Optional.empty();
    }

    private Optional<RejectionReason> nullSemanticsCheck(IntentClause clause, DefinitionView view) {
        ComparisonOperator operator = clause.operator();
        if (operator != null && NEGATIVE_OPERATORS.contains(operator)
                && view.nullSemantics() != NullSemantics.EXCLUDED) {
            return Optional.of(RejectionReason.NULL_INCLUSION_AMBIGUOUS);
        }
        return Optional.empty();
    }

    // --- evidence tier -------------------------------------------------------------------------

    private CandidateTier tierFor(
            IntentClause clause, DefinitionView view, String spanText, List<EvidenceCode> evidence) {
        CandidateTier best = CandidateTier.STRUCTURAL_MATCH;
        if (clause.candidateHints().contains(view.definition().id())) {
            evidence.add(EvidenceCode.EXACT_STABLE_ID_HINT);
            best = stronger(best, CandidateTier.EXACT_STABLE_ID_HINT);
        }
        String mapped = policy.conceptDefinitionId(clause.concept());
        if (mapped != null && mapped.equals(view.definition().id())) {
            evidence.add(EvidenceCode.CONCEPT_POLICY_MATCH);
            best = stronger(best, CandidateTier.EXACT_CONCEPT_POLICY_MATCH);
        }
        String key = spanText == null ? "" : MetadataTextNormalizer.normalize(spanText);
        if (!key.isEmpty()) {
            if (MetadataTextNormalizer.normalize(view.displayName()).equals(key)) {
                evidence.add(EvidenceCode.EXACT_DISPLAY_MATCH);
                best = stronger(best, CandidateTier.EXACT_DISPLAY_MATCH);
            }
            for (String alias : view.aliases()) {
                if (MetadataTextNormalizer.normalize(alias).equals(key)) {
                    evidence.add(EvidenceCode.EXACT_ALIAS_MATCH);
                    best = stronger(best, CandidateTier.EXACT_ALIAS_MATCH);
                    break;
                }
            }
            if (groundedTermLength(key, view) > 0) {
                evidence.add(EvidenceCode.SOURCE_TERM_MATCH);
            }
            for (String example : view.positiveExamples()) {
                if (MetadataTextNormalizer.normalize(example).equals(key)) {
                    evidence.add(EvidenceCode.POSITIVE_EXAMPLE_MATCH);
                    best = stronger(best, CandidateTier.POSITIVE_EXAMPLE_MATCH);
                    break;
                }
            }
        }
        return best;
    }

    private CandidateEvidence groundedPolicyCandidate(
            IntentClause clause, String spanText, List<CandidateEvidence> candidates) {
        if (candidates.size() < 2 || spanText == null) {
            return null;
        }
        String mappedId = policy.conceptDefinitionId(clause.concept());
        CandidateEvidence mapped = candidates.stream()
                .filter(candidate -> candidate.definitionId().equals(mappedId))
                .findFirst()
                .orElse(null);
        if (mapped == null || !mapped.evidence().contains(EvidenceCode.CONCEPT_POLICY_MATCH)) {
            return null;
        }
        if (mapped.evidence().contains(EvidenceCode.EXACT_DISPLAY_MATCH)
                || mapped.evidence().contains(EvidenceCode.EXACT_ALIAS_MATCH)) {
            return mapped;
        }
        String normalizedSpan = MetadataTextNormalizer.normalize(spanText);
        int mappedLength = groundedTermLength(normalizedSpan, definitionView(mapped.definitionId()));
        if (mappedLength == 0) {
            return null;
        }
        int longest = 0;
        int longestCount = 0;
        for (CandidateEvidence candidate : candidates) {
            int length = groundedTermLength(normalizedSpan, definitionView(candidate.definitionId()));
            if (length > longest) {
                longest = length;
                longestCount = 1;
            } else if (length == longest && length > 0) {
                longestCount++;
            }
        }
        return mappedLength == longest && longestCount == 1 ? mapped : null;
    }

    /**
     * Accepts the model's semantic selection only when its one stable-id hint agrees with the independently
     * versioned concept policy and that exact permitted candidate passed every structural and semantic check.
     * Neither a hint nor a concept alone can select among multiple compatible definitions.
     */
    private CandidateEvidence consensusCandidate(
            String concept, List<String> candidateHints, List<CandidateEvidence> candidates) {
        if (candidateHints.size() != 1) {
            return null;
        }
        String hintedId = candidateHints.get(0);
        String mappedId = policy.conceptDefinitionId(concept);
        if (!hintedId.equals(mappedId)) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> candidate.definitionId().equals(hintedId))
                .filter(candidate -> candidate.evidence().contains(EvidenceCode.EXACT_STABLE_ID_HINT))
                .filter(candidate -> candidate.evidence().contains(EvidenceCode.CONCEPT_POLICY_MATCH))
                .findFirst()
                .orElse(null);
    }

    private DefinitionView definitionView(String definitionId) {
        for (AttributeDefinition definition : snapshot.attributes()) {
            if (definition.id().equals(definitionId)) {
                return DefinitionView.of(definition);
            }
        }
        for (MetricDefinition definition : snapshot.metrics()) {
            if (definition.id().equals(definitionId)) {
                return DefinitionView.of(definition);
            }
        }
        for (RelationDefinition definition : snapshot.relations()) {
            if (definition.id().equals(definitionId)) {
                return DefinitionView.of(definition);
            }
        }
        throw new ResolutionException(
                ResolutionErrorCodes.OPTION_INCOMPATIBLE,
                "A resolution candidate is no longer present in the permitted snapshot");
    }

    private static int groundedTermLength(String normalizedSpan, DefinitionView view) {
        String displayName = MetadataTextNormalizer.normalize(view.displayName());
        int longest = groundedTermLength(normalizedSpan, displayName, false);
        for (String alias : view.aliases()) {
            String normalizedAlias = MetadataTextNormalizer.normalize(alias);
            longest = Math.max(
                    longest,
                    groundedTermLength(normalizedSpan, normalizedAlias, displayName.contains(normalizedAlias)));
        }
        return longest;
    }

    private static int groundedTermLength(String text, String term, boolean displayNameContainsTerm) {
        if (text.isEmpty() || !isGroundableTerm(term, displayNameContainsTerm)) {
            return 0;
        }
        int fromIndex = 0;
        while (fromIndex <= text.length() - term.length()) {
            int index = text.indexOf(term, fromIndex);
            if (index < 0) {
                return 0;
            }
            int end = index + term.length();
            boolean startsAsciiWord = isAsciiWord(term.charAt(0));
            boolean endsAsciiWord = isAsciiWord(term.charAt(term.length() - 1));
            boolean leftBounded = !startsAsciiWord || index == 0 || !isAsciiWord(text.charAt(index - 1));
            boolean rightBounded = !endsAsciiWord || end == text.length() || !isAsciiWord(text.charAt(end));
            if (leftBounded && rightBounded) {
                return term.length();
            }
            fromIndex = index + 1;
        }
        return 0;
    }

    private static boolean isGroundableTerm(String term, boolean displayNameContainsTerm) {
        int codePoints = term.codePointCount(0, term.length());
        if (codePoints >= 3) {
            return true;
        }
        return displayNameContainsTerm && codePoints == 2 && term.codePoints()
                .allMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
    }

    private static boolean isAsciiWord(char value) {
        return value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '_';
    }

    /** The stronger of two tiers; declaration order is the sort order, so the lower ordinal wins. */
    private static CandidateTier stronger(CandidateTier left, CandidateTier right) {
        return left.ordinal() <= right.ordinal() ? left : right;
    }

    // --- clarification -------------------------------------------------------------------------

    private Clarification buildClarification(
            SegmentIntent intent, ContentHash baseIntentHash, ResolutionPins pins, List<ClauseResolution> clauses,
            List<AudienceReferenceResolution> references, List<QuestionCode> globalQuestions) {

        // An audience reference that needs input is a first-class clarification target: it offers already-safe
        // choices. A clause question still takes precedence, because a reference is resolved from the same
        // declared request and its own wording is never more fundamental than an unparsed clause.
        AudienceReferenceResolution referenceTarget = null;
        for (AudienceReferenceResolution reference : references) {
            if (reference.needsInput() && !reference.choices().isEmpty()) {
                referenceTarget = reference;
                break;
            }
        }
        ClauseResolution target = null;
        for (ClauseResolution clause : clauses) {
            if (!clause.questionCodes().isEmpty()) {
                target = clause;
                break;
            }
        }
        if (target == null && !clauses.isEmpty()) {
            target = clauses.get(0);
        }
        String selectionTarget = target == null ? null : target.clauseId();
        List<CandidateEvidence> candidates = target == null ? List.of() : target.candidates();
        List<QuestionCode> targetQuestions = target == null ? List.of() : target.questionCodes();
        if (target != null && globalQuestions.isEmpty()) {
            for (ScopeFilterResolution scopeFilter : target.scopeFilters()) {
                if (!scopeFilter.questionCodes().isEmpty()) {
                    selectionTarget = scopeSelectionKey(target.clauseId(), scopeFilter.filterId());
                    candidates = scopeFilter.candidates();
                    targetQuestions = scopeFilter.questionCodes();
                    break;
                }
            }
        }
        if (globalQuestions.isEmpty() && targetQuestions.isEmpty() && referenceTarget != null) {
            return referenceClarification(baseIntentHash, pins, referenceTarget);
        }
        if (target == null) {
            throw new ResolutionException(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A clarification requires one answerable clause or audience reference");
        }
        // An intent-level ambiguity is the most fundamental blocker, so it takes precedence over a
        // clause-level one; otherwise the first unresolved clause or scope filter drives the clarification.
        List<QuestionCode> questions = !globalQuestions.isEmpty() ? globalQuestions : targetQuestions;
        QuestionCode questionCode = questions.isEmpty() ? QuestionCode.SELECT_DEFINITION : questions.get(0);
        IntentWindowAmbiguity windowAmbiguity = questionCode == QuestionCode.SELECT_WINDOW
                && !intent.windowAmbiguities().isEmpty()
                ? intent.windowAmbiguities().get(0)
                : null;
        IntentFilterScopeAmbiguity filterScopeAmbiguity = questionCode == QuestionCode.AMBIGUOUS_FILTER_SCOPE
                && !intent.filterScopeAmbiguities().isEmpty()
                ? intent.filterScopeAmbiguities().get(0)
                : null;
        if (windowAmbiguity != null) {
            selectionTarget = windowAmbiguity.targetPath();
        } else if (filterScopeAmbiguity != null) {
            selectionTarget = filterScopeAmbiguity.targetPath();
        }

        // Only a definition-selection question may expose definition options. A grouping, currency, NULL,
        // window, relation-scope or metric-meaning question must not offer an unrelated definition as a way
        // to "answer" it, so it carries no options at all and requires a revised Intent.
        List<ClarificationOption> options = new ArrayList<>();
        if (questionCode.isDefinitionSelection()) {
            for (CandidateEvidence candidate : candidates) {
                MetadataDefinition definition = findDefinition(candidate.definitionId());
                String label = safeLabel(definition);
                options.add(new ClarificationOption(
                        ClarificationOption.deriveOptionId(selectionTarget, candidate.definitionId()),
                        candidate.definitionId(),
                        label));
            }
        } else if (windowAmbiguity != null) {
            for (IntentWindowAlternative alternative : windowAmbiguity.options()) {
                String optionValue = windowAlternativeValue(alternative);
                options.add(new ClarificationOption(
                        ClarificationOption.deriveOptionId(selectionTarget, optionValue),
                        optionValue,
                        windowAlternativeLabel(intent, alternative)));
            }
        }
        List<String> optionIds = new ArrayList<>(options.size());
        for (ClarificationOption option : options) {
            optionIds.add(option.optionId());
        }
        ResolutionId resolutionId =
                ResolutionId.derive(baseIntentHash, pins, questionCode, selectionTarget, optionIds);
        return new Clarification(
                resolutionId, baseIntentHash, pins, questionCode, selectionTarget,
                questionText(questionCode, options), options);
    }

    private static String scopeSelectionKey(String clauseId, String filterId) {
        return clauseId + "/scope/" + filterId;
    }

    /**
     * The one structured clarification an audience reference produces.
     *
     * <p>Its choices are exactly the already-safe summaries the host-owned directory offered — an audience
     * identity and a presentation label, or an exact immutable pin key and a label — and its target is the
     * reference identity or that identity's version target. The complete option set is covered by the
     * resolution id, so a later answer that names a value which was never offered fails as stale, and a value
     * that was offered can only be one the directory itself produced.
     *
     * <p>No other question kind may be answered by an audience choice: a grouping, currency, NULL, window or
     * metric-meaning question carries no options at all, exactly as it does for a clause.
     */
    private static Clarification referenceClarification(
            ContentHash baseIntentHash, ResolutionPins pins, AudienceReferenceResolution reference) {
        QuestionCode questionCode = reference.questionCode();
        String selectionTarget = questionCode == QuestionCode.SELECT_AUDIENCE_VERSION
                ? AudienceReferenceResolver.versionSelectionTarget(reference.referenceId())
                : reference.referenceId();
        List<ClarificationOption> options = new ArrayList<>(reference.choices().size());
        for (AudienceReferenceChoice choice : reference.choices()) {
            options.add(new ClarificationOption(
                    ClarificationOption.deriveOptionId(selectionTarget, choice.value()),
                    choice.value(),
                    choice.displayLabel()));
        }
        List<String> optionIds = new ArrayList<>(options.size());
        for (ClarificationOption option : options) {
            optionIds.add(option.optionId());
        }
        ResolutionId resolutionId =
                ResolutionId.derive(baseIntentHash, pins, questionCode, selectionTarget, optionIds);
        return new Clarification(
                resolutionId, baseIntentHash, pins, questionCode, selectionTarget,
                questionText(questionCode, options), options);
    }

    private MetadataDefinition findDefinition(String definitionId) {
        for (MetadataDefinition definition : snapshot.attributes()) {
            if (definition.id().equals(definitionId)) {
                return definition;
            }
        }
        for (MetadataDefinition definition : snapshot.metrics()) {
            if (definition.id().equals(definitionId)) {
                return definition;
            }
        }
        for (MetadataDefinition definition : snapshot.relations()) {
            if (definition.id().equals(definitionId)) {
                return definition;
            }
        }
        return null;
    }

    private static String safeLabel(MetadataDefinition definition) {
        String label = definition == null ? "" : definition.displayName();
        if (label == null || label.isEmpty()) {
            label = "definition";
        }
        return label.length() > ResolutionLimits.MAX_OPTION_LABEL_LENGTH
                ? label.substring(0, ResolutionLimits.MAX_OPTION_LABEL_LENGTH)
                : label;
    }

    private static String questionText(QuestionCode code, List<ClarificationOption> options) {
        if (code.isDefinitionSelection() && !options.isEmpty()) {
            return "请选择该条件对应的字段："
                    + String.join("、", options.stream().map(ClarificationOption::label).toList())
                    + "。";
        }
        return switch (code) {
            case SELECT_DEFINITION -> "请选择该条件对应的字段。";
            case DISAMBIGUATE_DEFINITION -> "存在多个匹配字段，请选择一个。";
            case MISSING_CURRENCY -> "请确认该金额条件使用的币种。";
            case AMBIGUOUS_GROUPING -> "AND/OR 组合关系不明确，请确认条件分组。";
            case AMBIGUOUS_METRIC_MEANING -> "指标含义不明确，请确认要使用的指标。";
            case AMBIGUOUS_NULL_INCLUSION -> "请确认是否包含字段值为 NULL 的会员。";
            case AMBIGUOUS_RELATION_SCOPE -> "关联范围不明确，请确认关联范围。";
            case AMBIGUOUS_FILTER_SCOPE -> "筛选条件的作用范围不明确，请确认它是否同时约束后续指标。";
            case MISSING_METADATA_DEFINITION ->
                    "当前 Metadata 没有能直接表达该业务分类的字段，请说明它由哪个字段或计算规则判断。";
            case SELECT_WINDOW -> options.isEmpty()
                    ? "请选择条件使用的时间窗口。"
                    : "时间范围存在多种合理解释，请选择："
                    + String.join("、", options.stream().map(ClarificationOption::label).toList())
                    + "。";
            case UNSUPPORTED_REQUIREMENT -> "当前不支持该需求。";
            case SELECT_AUDIENCE_REFERENCE -> "找到多个可能的人群，请选择要引用的人群："
                    + String.join("、", options.stream().map(ClarificationOption::label).toList())
                    + "。";
            case SELECT_AUDIENCE_VERSION -> "该人群有多个已发布版本，请选择要引用的版本："
                    + String.join("、", options.stream().map(ClarificationOption::label).toList())
                    + "。";
            case AUDIENCE_REFERENCE_UNAVAILABLE -> "当前无法确定要引用的人群或其可用版本，请补充具体人群名称。";
        };
    }

    private static String windowAlternativeValue(IntentWindowAlternative alternative) {
        if (alternative instanceof IntentWindowAlternative.InheritWindow inherit) {
            return "inherit-window:" + inherit.fromClauseId();
        }
        IntentWindow window = ((IntentWindowAlternative.ExplicitWindow) alternative).window();
        return "window:" + window.kind().name() + ":"
                + new com.company.campaign.shared.canonical.CanonicalJson().hash(window).value();
    }

    private static String windowAlternativeLabel(SegmentIntent intent, IntentWindowAlternative alternative) {
        if (alternative instanceof IntentWindowAlternative.InheritWindow inherit) {
            IntentClause source = intent.clauses().stream()
                    .filter(clause -> clause.clauseId().equals(inherit.fromClauseId()))
                    .findFirst().orElseThrow();
            return "沿用前述时间范围（" + windowLabel(source.window()) + "）";
        }
        return windowLabel(((IntentWindowAlternative.ExplicitWindow) alternative).window());
    }

    private static String windowLabel(IntentWindow window) {
        return switch (window) {
            case IntentWindow.Rolling rolling -> "最近" + rolling.amount()
                    + (rolling.unit() == com.company.campaign.segment.intent.IntentWindowUnit.DAY ? "天" : "周");
            case IntentWindow.Lifetime ignored -> "历史累计";
            case IntentWindow.Fixed fixed -> fixed.start() + "至" + fixed.end();
            case IntentWindow.CalendarMonths calendar -> "最近" + calendar.count() + "个自然月";
            case IntentWindow.RelativeToAttribute ignored -> "相对属性时间范围";
            case IntentWindow.SelectedEvent ignored -> "所选事件本身";
            case IntentWindow.RelativeToEvent ignored -> "相对所选事件时间范围";
        };
    }

    private static QuestionCode questionFor(RejectionReason reason) {
        return switch (reason) {
            case NULL_INCLUSION_AMBIGUOUS -> QuestionCode.AMBIGUOUS_NULL_INCLUSION;
            case CURRENCY_MISSING -> QuestionCode.MISSING_CURRENCY;
            case NEGATIVE_EXAMPLE_CONFLICT, SEMANTIC_POLICY_CONFLICT -> QuestionCode.DISAMBIGUATE_DEFINITION;
            default -> QuestionCode.SELECT_DEFINITION;
        };
    }

    private static void addOnce(List<QuestionCode> questions, QuestionCode code) {
        if (!questions.contains(code)) {
            questions.add(code);
        }
    }

    private static String spanText(IntentClause clause) {
        return spanText(clause.provenance());
    }

    private static String spanText(IntentProvenance provenance) {
        if (provenance instanceof IntentProvenance.FromSourceSpan fromSpan) {
            return fromSpan.span().text();
        }
        return null;
    }

    // --- unified definition view ---------------------------------------------------------------

    /**
     * A read-only, non-disclosing projection of any supported definition kind, without a cast that can fail
     * for a Relation. A relation has no data type, operators, windows, aggregation or referenced relation id;
     * its subject is its source entity, and its declared scope is carried for exact scope comparison.
     */
    private record DefinitionView(
            MetadataDefinition definition,
            MetadataKind kind,
            SubjectKind subject,
            SemanticDataType dataType,
            List<ComparisonOperator> allowedOperators,
            List<String> positiveExamples,
            List<String> negativeExamples,
            NullSemantics nullSemantics,
            List<TimeWindowKind> supportedWindows,
            AggregateFunction aggregation,
            String relationId,
            CurrencySemantics currencySemantics,
            String displayName,
            List<String> aliases,
            RelationScope relationScope
    ) {

        static DefinitionView of(MetadataDefinition definition) {
            if (definition instanceof AttributeDefinition attribute) {
                return new DefinitionView(
                        attribute, MetadataKind.ATTRIBUTE, attribute.subject(), attribute.dataType(),
                        attribute.allowedOperators(), attribute.positiveExamples(), attribute.negativeExamples(),
                        attribute.nullSemantics(), List.of(), null, null, attribute.currencySemantics(),
                        attribute.displayName(), attribute.aliases(), null);
            }
            if (definition instanceof MetricDefinition metric) {
                return new DefinitionView(
                        metric, MetadataKind.METRIC, metric.subject(), metric.resultType(),
                        metric.allowedOperators(), metric.positiveExamples(), metric.negativeExamples(),
                        metric.nullSemantics(), metric.supportedWindows(), metric.aggregation(),
                        metric.relationId(), metric.currencySemantics(), metric.displayName(), metric.aliases(),
                        null);
            }
            RelationDefinition relation = (RelationDefinition) definition;
            return new DefinitionView(
                    relation, MetadataKind.RELATION, relation.sourceEntity(), null,
                    List.of(), relation.positiveExamples(), relation.negativeExamples(),
                    relation.targetNullSemantics(), List.of(), null, relation.id(), null,
                    relation.displayName(), relation.aliases(), relation.scope());
        }
    }
}
