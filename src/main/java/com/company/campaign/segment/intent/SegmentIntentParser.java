package com.company.campaign.segment.intent;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.canonical.ContentHashException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict tree reader for the structured Intent document.
 *
 * <p>It performs every shape, arity, field-membership and discriminator check itself rather than delegating
 * to Jackson binding, so an unknown or reserved property, a missing required field, a {@code NOT} with the
 * wrong child shape, a raw map in place of a typed value and an unknown {@code kind}/{@code mode}/{@code type}
 * are each rejected with a stable code. Nothing recurses without a depth bound, and no failure message or
 * detail echoes the utterance, span text or candidate labels.
 */
final class SegmentIntentParser {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "intentVersion", "subject", "clauses", "logicalExpression",
            "ambiguities", "unsupportedRequirements", "audienceReferences");

    /** The closed field set of one first-class audience reference: identity, candidates and provenance. */
    private static final Set<String> AUDIENCE_REFERENCE_FIELDS =
            Set.of("referenceId", "candidateTexts", "provenance");

    private static final Set<String> CLAUSE_FIELDS = Set.of(
            "clauseId", "concept", "definitionKind", "negated", "operator", "value", "window",
            "currency", "aggregation", "relationScope", "scopeFilters", "provenance", "candidateHints",
            "assumptions");

    private static final Set<String> SCOPE_FILTER_FIELDS = Set.of(
            "filterId", "concept", "domain", "identifierKind", "operator", "value", "provenance",
            "candidateHints", "assumptions");

    private static final Set<String> PROVENANCE_SPAN_FIELDS = Set.of("type", "text", "start", "end");
    private static final Set<String> PROVENANCE_DEFAULT_FIELDS =
            Set.of("type", "defaultId", "version", "contentHash");

    private static final Set<String> VALUE_SCALAR_FIELDS = Set.of("kind", "raw");
    private static final Set<String> VALUE_LIST_FIELDS = Set.of("kind", "items");
    private static final Set<String> VALUE_RANGE_FIELDS =
            Set.of("kind", "lower", "upper", "includeLower", "includeUpper");

    private static final Set<String> WINDOW_ROLLING_FIELDS = Set.of("kind", "amount", "unit");
    private static final Set<String> WINDOW_LIFETIME_FIELDS = Set.of("kind");
    private static final Set<String> WINDOW_FIXED_FIELDS = Set.of("kind", "start", "end");
    private static final Set<String> WINDOW_CALENDAR_MONTH_FIELDS =
            Set.of("kind", "count", "includeCurrent", "bucketRequirement");
    private static final Set<String> WINDOW_RELATIVE_ATTRIBUTE_FIELDS =
            Set.of("kind", "anchorDefinitionId", "startOffset", "endOffset", "unit");
    private static final Set<String> WINDOW_SELECTED_EVENT_FIELDS =
            Set.of("kind", "selectorId", "selectorScopeFilters");
    private static final Set<String> WINDOW_RELATIVE_EVENT_FIELDS =
            Set.of("kind", "selectorId", "selectorScopeFilters", "startOffset", "endOffset", "unit",
                    "excludeAnchorEvent");
    private static final Set<String> STRUCTURED_AMBIGUITY_FIELDS = Set.of("code", "target", "options");
    private static final Set<String> WINDOW_INHERIT_FIELDS = Set.of("kind", "fromClauseId");
    private static final Set<String> FILTER_SCOPE_INHERIT_FIELDS = Set.of("kind", "fromClauseId");
    private static final Set<String> FILTER_SCOPE_INDEPENDENT_FIELDS = Set.of("kind");

    private static final Set<String> CURRENCY_EXPLICIT_FIELDS = Set.of("mode", "code");
    private static final Set<String> CURRENCY_DEFAULT_FIELDS =
            Set.of("mode", "defaultId", "version", "contentHash");

    private static final Set<String> RELATION_SCOPE_FIELDS =
            Set.of("joinKind", "sourceJoinField", "targetJoinField");

    private static final Set<String> EXPRESSION_CLAUSE_FIELDS = Set.of("type", "clauseId");
    private static final Set<String> EXPRESSION_GROUP_FIELDS = Set.of("type", "children");
    private static final Set<String> EXPRESSION_NOT_FIELDS = Set.of("type", "child");

    /** Host-owned or executable properties Intent must never carry, even when otherwise unknown. */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "tenantId", "tenant", "programId", "program", "userId", "user", "dataScopeId", "dataScope",
            "permissions", "permission", "auth", "authorization", "sql", "query", "gate", "gateOutcome",
            "activation", "active", "save", "saveId", "publish", "published", "planId", "draftId",
            // An audience reference is resolved by the host, so every fact about the referenced version, its
            // owner, its dependencies, its retention and its Gate state is unresolvable from model output and
            // is refused by name rather than merely unknown. "current" and "latest" are refused for the same
            // reason the DSL refuses them: a mutable pointer is unrepresentable, not merely discouraged.
            "audienceId", "audienceVersion", "versionId", "versionHash", "versionPin", "current", "latest",
            "isLatest", "dependencies", "dependencyGraphHash", "retention", "retain", "retentionHoldId",
            "owner", "ownerTenantId", "ownerProgramId", "readiness", "capability", "capabilityId",
            "approvedDecisionHash", "gateInputsHash", "resolutionEvidence", "referenceDependencyHash");

    SegmentIntent parseRoot(JsonNode root, String utterance, RegisteredDefaultSource defaults) {
        requireObject(root, "the Intent document");
        requireOnlyFields(root, ROOT_FIELDS, "the Intent document");
        SchemaRootFields fields = new SchemaRootFields(root);
        List<IntentClause> clauses = parseClauses(requirePresent(root, "clauses"), utterance, defaults);
        Set<String> clauseIds = new LinkedHashSet<>();
        for (IntentClause clause : clauses) {
            clauseIds.add(clause.clauseId());
        }
        ParsedAmbiguities parsedAmbiguities = parseAmbiguities(
                fieldOrNull(root, "ambiguities"), utterance, defaults);
        return new SegmentIntent(
                fields.intentVersion(),
                fields.subject(),
                clauses,
                parseExpression(requirePresent(root, "logicalExpression"), 1),
                parsedAmbiguities.codes(),
                enumList(fieldOrNull(root, "unsupportedRequirements"), UnsupportedRequirement.class,
                        "unsupportedRequirements"),
                parsedAmbiguities.windowAmbiguities(),
                parsedAmbiguities.filterScopeAmbiguities(),
                parseAudienceReferences(fieldOrNull(root, "audienceReferences"), utterance, defaults));
    }

    /**
     * Reads the bounded first-class audience-reference array, or nothing when the member is absent.
     *
     * <p>The member is optional, so a document that carries no reference parses exactly as it always did. Each
     * entry is read strictly: exactly the three known names, a bounded candidate-text array with at least one
     * entry, and the one grounded provenance form. A version pin, an owner, a scope, a dependency or a Gate fact
     * cannot appear here — every one of those names is a reserved name, so declaring one is a refusal rather
     * than a silently dropped field.
     */
    private List<IntentAudienceReference> parseAudienceReferences(
            JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray() || node.isEmpty() || node.size() > IntentLimits.MAX_AUDIENCE_REFERENCES) {
            throw IntentException.invalid(
                    IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "Intent audience references must be a non-empty bounded JSON array");
        }
        List<IntentAudienceReference> references = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            requireObject(element, "an Intent audience reference");
            requireOnlyFields(element, AUDIENCE_REFERENCE_FIELDS, "an Intent audience reference");
            List<String> candidates = textList(
                    requirePresent(element, "candidateTexts"), "audienceReference.candidateTexts");
            references.add(new IntentAudienceReference(
                    text(requirePresent(element, "referenceId"), "audienceReference.referenceId"),
                    candidates == null ? List.of() : candidates,
                    parseProvenance(requirePresent(element, "provenance"), utterance, defaults)));
        }
        return List.copyOf(references);
    }

    private ParsedAmbiguities parseAmbiguities(
            JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        if (node == null) {
            return new ParsedAmbiguities(List.of(), List.of(), List.of());
        }
        if (!node.isArray() || node.size() > IntentLimits.MAX_CODE_ENTRIES) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent ambiguities must be a bounded JSON array");
        }
        Set<IntentAmbiguity> codes = new LinkedHashSet<>();
        List<IntentWindowAmbiguity> windows = new ArrayList<>();
        List<IntentFilterScopeAmbiguity> filterScopes = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                IntentAmbiguity code = enumValue(entry, IntentAmbiguity.class, "ambiguities");
                if (!codes.add(code)) {
                    throw IntentException.invalid(
                            IntentErrorCodes.MACHINE_CODE_INVALID, "Intent ambiguities repeat an entry");
                }
                continue;
            }
            requireObject(entry, "a structured ambiguity");
            requireOnlyFields(entry, STRUCTURED_AMBIGUITY_FIELDS, "a structured ambiguity");
            IntentAmbiguity code = enumValue(
                    requirePresent(entry, "code"), IntentAmbiguity.class, "ambiguity.code");
            if (code != IntentAmbiguity.AMBIGUOUS_WINDOW
                    && code != IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE) {
                throw IntentException.invalid(
                        IntentErrorCodes.UNKNOWN_DISCRIMINATOR,
                        "The ambiguity code has no structured representation");
            }
            codes.add(code);
            String target = text(requirePresent(entry, "target"), "ambiguity.target");
            if (code == IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE) {
                filterScopes.add(parseFilterScopeAmbiguity(entry, target));
                continue;
            }
            if (!target.endsWith(".window") || target.length() <= ".window".length()) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "A structured window ambiguity must target one clause window");
            }
            String targetClauseId = target.substring(0, target.length() - ".window".length());
            JsonNode optionsNode = requirePresent(entry, "options");
            if (!optionsNode.isArray() || optionsNode.size() < 2 || optionsNode.size() > 8) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "A structured window ambiguity requires bounded alternatives");
            }
            List<IntentWindowAlternative> options = new ArrayList<>(optionsNode.size());
            for (JsonNode option : optionsNode) {
                requireObject(option, "a window ambiguity alternative");
                JsonNode kind = requirePresent(option, "kind");
                if (kind.isTextual() && "INHERIT_WINDOW".equals(kind.textValue())) {
                    requireOnlyFields(option, WINDOW_INHERIT_FIELDS, "an inherited window alternative");
                    options.add(new IntentWindowAlternative.InheritWindow(
                            text(requirePresent(option, "fromClauseId"), "windowAlternative.fromClauseId")));
                } else {
                    options.add(new IntentWindowAlternative.ExplicitWindow(
                            parseWindow(option, utterance, defaults)));
                }
            }
            windows.add(new IntentWindowAmbiguity(targetClauseId, options));
        }
        return new ParsedAmbiguities(
                List.copyOf(codes), List.copyOf(windows), List.copyOf(filterScopes));
    }

    private IntentFilterScopeAmbiguity parseFilterScopeAmbiguity(JsonNode entry, String target) {
        String suffix = ".scopeFilters";
        if (!target.endsWith(suffix) || target.length() <= suffix.length()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "A structured filter-scope ambiguity must target one clause scopeFilters field");
        }
        JsonNode optionsNode = requirePresent(entry, "options");
        if (!optionsNode.isArray() || optionsNode.size() < 2 || optionsNode.size() > 8) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "A structured filter-scope ambiguity requires bounded alternatives");
        }
        List<IntentFilterScopeAlternative> options = new ArrayList<>(optionsNode.size());
        for (JsonNode option : optionsNode) {
            requireObject(option, "a filter-scope ambiguity alternative");
            String kind = text(requirePresent(option, "kind"), "filterScopeAlternative.kind");
            if ("INHERIT_SCOPE_FILTERS".equals(kind)) {
                requireOnlyFields(option, FILTER_SCOPE_INHERIT_FIELDS, "an inherited filter-scope alternative");
                options.add(new IntentFilterScopeAlternative.InheritScopeFilters(
                        text(requirePresent(option, "fromClauseId"),
                                "filterScopeAlternative.fromClauseId")));
            } else if ("INDEPENDENT".equals(kind)) {
                requireOnlyFields(option, FILTER_SCOPE_INDEPENDENT_FIELDS,
                        "an independent filter-scope alternative");
                options.add(new IntentFilterScopeAlternative.Independent());
            } else {
                throw IntentException.invalid(
                        IntentErrorCodes.UNKNOWN_DISCRIMINATOR,
                        "The filter-scope alternative kind is not supported");
            }
        }
        return new IntentFilterScopeAmbiguity(
                target.substring(0, target.length() - suffix.length()), options);
    }

    private record ParsedAmbiguities(
            List<IntentAmbiguity> codes,
            List<IntentWindowAmbiguity> windowAmbiguities,
            List<IntentFilterScopeAmbiguity> filterScopeAmbiguities) {
    }

    /** Validates the version and subject without duplicating the work in the record constructor. */
    private record SchemaRootFields(com.company.campaign.contracts.SchemaVersion intentVersion, SubjectKind subject) {

        SchemaRootFields(JsonNode root) {
            this(parseVersion(requirePresent(root, "intentVersion")), parseSubject(requirePresent(root, "subject")));
        }

        private static com.company.campaign.contracts.SchemaVersion parseVersion(JsonNode node) {
            if (!node.isTextual()) {
                throw IntentException.invalid(
                        IntentErrorCodes.VERSION_UNSUPPORTED, "The Intent version must be canonical text");
            }
            try {
                return com.company.campaign.contracts.SchemaVersion.parse(node.textValue());
            } catch (RuntimeException exception) {
                throw IntentException.invalid(
                        IntentErrorCodes.VERSION_UNSUPPORTED, "The Intent version is not a canonical version");
            }
        }

        private static SubjectKind parseSubject(JsonNode node) {
            if (!node.isTextual()) {
                throw IntentException.invalid(
                        IntentErrorCodes.SUBJECT_INVALID, "The Intent subject must be a supported name");
            }
            try {
                return SubjectKind.valueOf(node.textValue());
            } catch (IllegalArgumentException exception) {
                throw IntentException.invalid(
                        IntentErrorCodes.SUBJECT_INVALID, "The Intent subject is not a supported subject");
            }
        }
    }

    private List<IntentClause> parseClauses(JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        if (!node.isArray()) {
            throw IntentException.invalid(
                    IntentErrorCodes.CLAUSES_INVALID, "Intent clauses must be a JSON array");
        }
        if (node.size() > IntentLimits.MAX_CLAUSES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The Intent exceeds the clause limit");
        }
        List<IntentClause> clauses = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            clauses.add(parseClause(element, utterance, defaults));
        }
        return clauses;
    }

    private IntentClause parseClause(JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        requireObject(node, "an Intent clause");
        requireOnlyFields(node, CLAUSE_FIELDS, "an Intent clause");
        IntentProvenance provenance = parseProvenance(requirePresent(node, "provenance"), utterance, defaults);
        return new IntentClause(
                text(requirePresent(node, "clauseId"), "clauseId"),
                text(requirePresent(node, "concept"), "concept"),
                optionalEnum(fieldOrNull(node, "definitionKind"), MetadataKind.class, "definitionKind"),
                bool(requirePresent(node, "negated"), "negated"),
                optionalEnum(fieldOrNull(node, "operator"), ComparisonOperator.class, "operator"),
                fieldOrNull(node, "value") == null ? null : parseValue(fieldOrNull(node, "value"), 1),
                fieldOrNull(node, "window") == null
                        ? null
                        : parseWindow(fieldOrNull(node, "window"), utterance, defaults),
                fieldOrNull(node, "currency") == null ? null : parseCurrency(fieldOrNull(node, "currency"), defaults),
                optionalEnum(fieldOrNull(node, "aggregation"), AggregateFunction.class, "aggregation"),
                fieldOrNull(node, "relationScope") == null
                        ? null : parseRelationScope(fieldOrNull(node, "relationScope")),
                parseScopeFilters(fieldOrNull(node, "scopeFilters"), utterance, defaults),
                provenance,
                textList(fieldOrNull(node, "candidateHints"), "candidateHints"),
                textList(fieldOrNull(node, "assumptions"), "assumptions"));
    }

    private List<IntentScopeFilter> parseScopeFilters(
            JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID, "Metric scope filters must be a JSON array");
        }
        if (node.size() > IntentLimits.MAX_SCOPE_FILTERS) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "A Metric clause exceeds the scope-filter limit");
        }
        List<IntentScopeFilter> filters = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            requireObject(element, "a Metric scope filter");
            requireOnlyFields(element, SCOPE_FILTER_FIELDS, "a Metric scope filter");
            filters.add(new IntentScopeFilter(
                    text(requirePresent(element, "filterId"), "scopeFilter.filterId"),
                    text(requirePresent(element, "concept"), "scopeFilter.concept"),
                    enumValue(requirePresent(element, "domain"), FilterDomain.class,
                            "scopeFilter.domain"),
                    enumValue(requirePresent(element, "identifierKind"), FilterIdentifierKind.class,
                            "scopeFilter.identifierKind"),
                    enumValue(requirePresent(element, "operator"), ComparisonOperator.class,
                            "scopeFilter.operator"),
                    fieldOrNull(element, "value") == null
                            ? null : parseValue(fieldOrNull(element, "value"), 1),
                    parseProvenance(requirePresent(element, "provenance"), utterance, defaults),
                    textList(fieldOrNull(element, "candidateHints"), "scopeFilter.candidateHints"),
                    textList(fieldOrNull(element, "assumptions"), "scopeFilter.assumptions")));
        }
        return List.copyOf(filters);
    }

    private IntentProvenance parseProvenance(JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        requireObject(node, "a clause provenance");
        String type = discriminator(node, "type", "SOURCE_SPAN", "REGISTERED_DEFAULT");
        if ("SOURCE_SPAN".equals(type)) {
            requireOnlyFields(node, PROVENANCE_SPAN_FIELDS, "a source-span provenance");
            SourceSpan span = new SourceSpan(
                    text(requirePresent(node, "text"), "provenance.text"),
                    integer(requirePresent(node, "start"), "provenance.start"),
                    integer(requirePresent(node, "end"), "provenance.end"));
            if (!span.isEmpty()) {
                SourceSpanValidator.validate(utterance, span);
            } else {
                throw IntentException.invalid(
                        IntentErrorCodes.SPAN_EMPTY, "A clause source span must carry user text");
            }
            return new IntentProvenance.FromSourceSpan(span);
        }
        requireOnlyFields(node, PROVENANCE_DEFAULT_FIELDS, "a registered-default provenance");
        String defaultId = text(requirePresent(node, "defaultId"), "provenance.defaultId");
        String version = text(requirePresent(node, "version"), "provenance.version");
        ContentHash hash = contentHash(requirePresent(node, "contentHash"));
        RegisteredDefaultRevisionVerifier.verify(defaults, defaultId, version, hash);
        return new IntentProvenance.FromRegisteredDefault(defaultId, version, hash);
    }

    private IntentValue parseValue(JsonNode node, int depth) {
        if (depth > IntentLimits.MAX_EXPRESSION_DEPTH) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "An Intent value exceeds the nesting limit");
        }
        requireObject(node, "an Intent value");
        IntentValueKind kind = enumValue(requirePresent(node, "kind"), IntentValueKind.class, "value.kind");
        return switch (kind) {
            case LIST -> parseListValue(node, depth);
            case RANGE -> parseRangeValue(node, depth);
            default -> parseScalarValue(node, kind);
        };
    }

    private IntentValue parseScalarValue(JsonNode node, IntentValueKind kind) {
        requireOnlyFields(node, VALUE_SCALAR_FIELDS, "a scalar value");
        JsonNode raw = requirePresent(node, "raw");
        return switch (kind) {
            case STRING -> new IntentValue.StringValue(anyText(raw, "value.raw"));
            case BOOLEAN -> new IntentValue.BooleanValue(bool(raw, "value.raw"));
            case INTEGER -> new IntentValue.IntegerValue(anyText(raw, "value.raw"));
            case DECIMAL -> new IntentValue.DecimalValue(anyText(raw, "value.raw"));
            case DATE -> new IntentValue.DateValue(anyText(raw, "value.raw"));
            case TIMESTAMP -> new IntentValue.TimestampValue(anyText(raw, "value.raw"));
            case ENUM -> new IntentValue.EnumValue(anyText(raw, "value.raw"));
            case MONEY -> new IntentValue.MoneyValue(anyText(raw, "value.raw"));
            default -> throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A scalar value has an inadmissible kind");
        };
    }

    private IntentValue parseListValue(JsonNode node, int depth) {
        requireOnlyFields(node, VALUE_LIST_FIELDS, "a list value");
        JsonNode items = requirePresent(node, "items");
        if (!items.isArray() || items.isEmpty()) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A list value requires at least one item");
        }
        if (items.size() > IntentLimits.MAX_LIST_ITEMS) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "A list value exceeds the item limit");
        }
        List<IntentValue.Scalar> scalars = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            IntentValue parsed = parseValue(item, depth + 1);
            if (!(parsed instanceof IntentValue.Scalar scalar)) {
                throw IntentException.invalid(
                        IntentErrorCodes.VALUE_INVALID, "A list item must be a scalar value");
            }
            scalars.add(scalar);
        }
        return new IntentValue.ListValue(scalars);
    }

    private IntentValue parseRangeValue(JsonNode node, int depth) {
        requireOnlyFields(node, VALUE_RANGE_FIELDS, "a range value");
        IntentValue.Scalar lower = parseScalarBound(requirePresent(node, "lower"), depth);
        IntentValue.Scalar upper = parseScalarBound(requirePresent(node, "upper"), depth);
        return new IntentValue.RangeValue(
                lower,
                upper,
                bool(requirePresent(node, "includeLower"), "range.includeLower"),
                bool(requirePresent(node, "includeUpper"), "range.includeUpper"));
    }

    private IntentValue.Scalar parseScalarBound(JsonNode node, int depth) {
        IntentValue parsed = parseValue(node, depth + 1);
        if (!(parsed instanceof IntentValue.Scalar scalar)) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A range bound must be a scalar value");
        }
        return scalar;
    }

    private IntentWindow parseWindow(
            JsonNode node, String utterance, RegisteredDefaultSource defaults) {
        requireObject(node, "a window intent");
        JsonNode kindNode = requirePresent(node, "kind");
        if (!kindNode.isTextual()) {
            throw IntentException.invalid(IntentErrorCodes.WINDOW_INVALID, "A window requires a kind");
        }
        return switch (kindNode.textValue()) {
            case "ROLLING" -> {
                requireOnlyFields(node, WINDOW_ROLLING_FIELDS, "a rolling window");
                yield new IntentWindow.Rolling(
                        integer(requirePresent(node, "amount"), "window.amount"),
                        windowEnumValue(requirePresent(node, "unit"), IntentWindowUnit.class, "window.unit"));
            }
            case "LIFETIME" -> {
                requireOnlyFields(node, WINDOW_LIFETIME_FIELDS, "a lifetime window");
                yield new IntentWindow.Lifetime();
            }
            case "FIXED" -> {
                requireOnlyFields(node, WINDOW_FIXED_FIELDS, "a fixed window");
                yield new IntentWindow.Fixed(
                        anyText(requirePresent(node, "start"), "window.start"),
                        anyText(requirePresent(node, "end"), "window.end"));
            }
            case "CALENDAR_MONTHS" -> {
                requireOnlyFields(node, WINDOW_CALENDAR_MONTH_FIELDS, "a calendar-month window");
                yield new IntentWindow.CalendarMonths(
                        integer(requirePresent(node, "count"), "window.count"),
                        bool(requirePresent(node, "includeCurrent"), "window.includeCurrent"),
                        windowEnumValue(
                                requirePresent(node, "bucketRequirement"),
                                CalendarBucketRequirement.class,
                                "window.bucketRequirement"));
            }
            case "RELATIVE_TO_ATTRIBUTE" -> {
                requireOnlyFields(node, WINDOW_RELATIVE_ATTRIBUTE_FIELDS, "a relative-to-attribute window");
                yield new IntentWindow.RelativeToAttribute(
                        text(requirePresent(node, "anchorDefinitionId"), "window.anchorDefinitionId"),
                        integer(requirePresent(node, "startOffset"), "window.startOffset"),
                        integer(requirePresent(node, "endOffset"), "window.endOffset"),
                        windowEnumValue(requirePresent(node, "unit"), IntentWindowUnit.class, "window.unit"));
            }
            case "SELECTED_EVENT" -> {
                requireOnlyFields(node, WINDOW_SELECTED_EVENT_FIELDS, "a selected-event window");
                yield new IntentWindow.SelectedEvent(
                        text(requirePresent(node, "selectorId"), "window.selectorId"),
                        parseScopeFilters(fieldOrNull(node, "selectorScopeFilters"), utterance, defaults));
            }
            case "RELATIVE_TO_EVENT" -> {
                requireOnlyFields(node, WINDOW_RELATIVE_EVENT_FIELDS, "a relative-to-event window");
                yield new IntentWindow.RelativeToEvent(
                        text(requirePresent(node, "selectorId"), "window.selectorId"),
                        parseScopeFilters(fieldOrNull(node, "selectorScopeFilters"), utterance, defaults),
                        integer(requirePresent(node, "startOffset"), "window.startOffset"),
                        integer(requirePresent(node, "endOffset"), "window.endOffset"),
                        windowEnumValue(requirePresent(node, "unit"), IntentWindowUnit.class, "window.unit"),
                        bool(requirePresent(node, "excludeAnchorEvent"), "window.excludeAnchorEvent"));
            }
            default -> throw IntentException.invalid(
                    IntentErrorCodes.WINDOW_INVALID, "A window kind is unsupported");
        };
    }

    private IntentCurrency parseCurrency(JsonNode node, RegisteredDefaultSource defaults) {
        requireObject(node, "a currency intent");
        if (!node.has("mode") || !node.get("mode").isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.CURRENCY_INVALID, "A currency intent requires a mode discriminator");
        }
        String mode = node.get("mode").textValue();
        return switch (mode) {
            case "EXPLICIT" -> {
                requireOnlyFields(node, CURRENCY_EXPLICIT_FIELDS, "an explicit currency");
                yield new IntentCurrency.Explicit(anyText(requirePresent(node, "code"), "currency.code"));
            }
            case "REGISTERED_DEFAULT" -> {
                requireOnlyFields(node, CURRENCY_DEFAULT_FIELDS, "a registered-default currency");
                String defaultId = anyText(requirePresent(node, "defaultId"), "currency.defaultId");
                String version = anyText(requirePresent(node, "version"), "currency.version");
                ContentHash hash = contentHash(requirePresent(node, "contentHash"));
                RegisteredDefaultRevisionVerifier.verify(defaults, defaultId, version, hash);
                yield new IntentCurrency.RegisteredDefault(defaultId, version, hash);
            }
            default -> throw IntentException.invalid(
                    IntentErrorCodes.CURRENCY_INVALID, "A currency intent has an unknown mode");
        };
    }

    private RelationScope parseRelationScope(JsonNode node) {
        requireObject(node, "a relation scope");
        requireOnlyFields(node, RELATION_SCOPE_FIELDS, "a relation scope");
        return new RelationScope(
                enumValue(requirePresent(node, "joinKind"), RelationJoinKind.class, "relationScope.joinKind"),
                optionalText(fieldOrNull(node, "sourceJoinField"), "relationScope.sourceJoinField"),
                optionalText(fieldOrNull(node, "targetJoinField"), "relationScope.targetJoinField"));
    }

    private LogicalExpression parseExpression(JsonNode node, int depth) {
        if (depth > IntentLimits.MAX_EXPRESSION_DEPTH) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The logical expression exceeds the depth limit");
        }
        requireObject(node, "a logical expression node");
        if (!node.has("type") || !node.get("type").isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.EXPRESSION_INVALID, "A logical expression node requires a type");
        }
        String type = node.get("type").textValue();
        return switch (type) {
            case "CLAUSE" -> {
                requireOnlyFields(node, EXPRESSION_CLAUSE_FIELDS, "a clause reference");
                yield new LogicalExpression.Clause(anyText(requirePresent(node, "clauseId"), "expression.clauseId"));
            }
            case "ALL", "ANY" -> parseGroup(node, type, depth);
            case "NOT" -> {
                if (node.has("children")) {
                    throw IntentException.invalid(
                            IntentErrorCodes.EXPRESSION_NOT_ARITY, "A unary NOT requires exactly one child");
                }
                requireOnlyFields(node, EXPRESSION_NOT_FIELDS, "a NOT node");
                yield new LogicalExpression.Negation(
                        parseExpression(requirePresent(node, "child"), depth + 1));
            }
            default -> throw IntentException.invalid(
                    IntentErrorCodes.UNKNOWN_DISCRIMINATOR,
                    "A logical expression node has an unknown type");
        };
    }

    private LogicalExpression parseGroup(JsonNode node, String type, int depth) {
        requireOnlyFields(node, EXPRESSION_GROUP_FIELDS, "a logical group");
        JsonNode children = requirePresent(node, "children");
        if (!children.isArray()) {
            throw IntentException.invalid(
                    IntentErrorCodes.EXPRESSION_INVALID, "A logical group children value must be an array");
        }
        if (children.size() < 2 || children.size() > IntentLimits.MAX_EXPRESSION_WIDTH) {
            throw IntentException.invalid(
                    IntentErrorCodes.EXPRESSION_WIDTH_INVALID,
                    "A logical group requires between two and the width limit of children");
        }
        List<LogicalExpression> parsed = new ArrayList<>(children.size());
        for (JsonNode child : children) {
            parsed.add(parseExpression(child, depth + 1));
        }
        return new LogicalExpression.Group(LogicalOperator.valueOf(type), parsed);
    }

    // --- primitive readers --------------------------------------------------------------------

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Expected a JSON object for " + what);
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String what) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (allowed.contains(name)) {
                continue;
            }
            if (RESERVED_FIELDS.contains(name)) {
                throw IntentException.invalid(
                        IntentErrorCodes.RESERVED_FIELD,
                        "A reserved trusted or executable property is not part of the Intent contract");
            }
            throw IntentException.invalid(
                    IntentErrorCodes.UNKNOWN_FIELD,
                    "An unknown property is not part of the Intent contract");
        }
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "A required Intent property is missing");
        }
        return value;
    }

    private static JsonNode fieldOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value;
    }

    private static String discriminator(JsonNode node, String field, String... allowed) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "A discriminator property is missing");
        }
        String text = value.textValue();
        for (String candidate : allowed) {
            if (candidate.equals(text)) {
                return text;
            }
        }
        throw IntentException.invalid(
                IntentErrorCodes.UNKNOWN_DISCRIMINATOR, "A discriminator property is not a known variant");
    }

    private static String text(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent field '" + field + "' must be text");
        }
        return node.textValue();
    }

    private static String anyText(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent field '" + field + "' must be text");
        }
        return node.textValue();
    }

    private static boolean bool(JsonNode node, String field) {
        if (!node.isBoolean()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent field '" + field + "' must be a boolean");
        }
        return node.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent field '" + field + "' must be a bounded integer");
        }
        return node.intValue();
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        return text(node, field);
    }

    private static List<String> textList(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent list '" + field + "' must be an array");
        }
        if (node.size() > IntentLimits.MAX_LIST_ENTRIES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "Intent list '" + field + "' exceeds the entry limit");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            values.add(text(element, field));
        }
        return values;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        if (!node.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent field '" + field + "' must be a name");
        }
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.UNKNOWN_DISCRIMINATOR,
                    "Intent field '" + field + "' is not a known variant");
        }
    }

    /**
     * One closed-variant field that belongs to the window contract.
     *
     * <p>A window-scoped name outside the closed window vocabulary is a window-contract violation, so it maps to
     * {@link IntentErrorCodes#WINDOW_INVALID} rather than the generic unknown-discriminator code. That keeps the
     * adapter's stable failure code honest: a response whose window unit, bucket policy or shape is outside the
     * closed window contract fails as a window failure.
     */
    private static <E extends Enum<E>> E windowEnumValue(JsonNode node, Class<E> type, String field) {
        if (!node.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.WINDOW_INVALID, "Intent field '" + field + "' must be a window name");
        }
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.WINDOW_INVALID,
                    "Intent field '" + field + "' is outside the closed window contract");
        }
    }

    private static <E extends Enum<E>> E optionalEnum(JsonNode node, Class<E> type, String field) {
        return node == null ? null : enumValue(node, type, field);
    }

    private static <E extends Enum<E>> List<E> enumList(JsonNode node, Class<E> type, String field) {
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "Intent list '" + field + "' must be an array");
        }
        if (node.size() > IntentLimits.MAX_CODE_ENTRIES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "Intent list '" + field + "' exceeds the entry limit");
        }
        List<E> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            values.add(enumValue(element, type, field));
        }
        return values;
    }

    private static ContentHash contentHash(JsonNode node) {
        if (!node.isTextual()) {
            throw IntentException.invalid(
                    IntentErrorCodes.DEFAULT_REFERENCE_INVALID, "A content hash must be canonical text");
        }
        try {
            return new ContentHash(node.textValue());
        } catch (ContentHashException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.DEFAULT_REFERENCE_INVALID, "A content hash is malformed");
        }
    }
}
