package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the trusted reference graph of one document and freezes it into a deterministic hash.
 *
 * <p>Every fact it uses is trusted: the exact version comes from the host catalog, the program relation from
 * the host policy, the effective scope from the host DataScope port, and the reachability decision from the
 * injected fail-closed authorization boundary. Nothing is read from the DSL, from the canonical AST or from a
 * model payload, and nothing it produces is ever written back into the editable document.
 *
 * <p>For every direct and transitive reference it enforces, in one fixed order, that the exact version exists
 * and its bytes match the pin, that the owner tenant matches the caller, that the version is available, that
 * the host program policy permits the relation, that the caller may reach the segment, and that the trusted
 * intersection of the caller, parent and referenced scopes is non-empty. The reachability decision is
 * accepted only when the boundary returns an allowed decision whose disposition <em>and</em> reason are the
 * allowed ones, whose resource kind is exactly {@code SEGMENT} and whose required permission is exactly the
 * permission {@code SEGMENT} requires; a null, thrown, wrong-kind, wrong-permission or otherwise malformed
 * allowed decision is an unavailable decision and fails closed with the one non-disclosing finding. Cycles —
 * including a candidate edit whose graph reaches the version it is about to become — depth, distinct-version
 * count and expanded complexity are all bounded, and the traversal is depth-bounded rather than merely
 * cycle-checked, so a hostile catalog cannot drive it into unbounded recursion.
 *
 * <p>One reference is one exact pin: audience identity, version identity <em>and</em> version content hash.
 * Two sites that name one version identity with different bytes are two different references, so they are
 * looked up, resolved and accounted separately and never share one cached catalog answer. A pin the catalog
 * cannot confirm is refused on its own terms: it produces the normal non-disclosing conflict or not-found
 * finding, and no graph may be accepted for the document.
 *
 * <p>Every incoming path is validated rather than only the first. A version reachable through more than one
 * parent is re-checked against each parent subject and each parent effective DataScope, so a later path
 * cannot be skipped because an earlier one was accepted: a path whose parent disagrees about the subject is a
 * finding, and a path that proves a narrower — or empty — intersection narrows, or refuses, what that version
 * may reach. Dependency accounting stays once per exact pin and is deliberately kept separate from that path
 * validation, so "one version is depended on" and "one path was proved" are never confused.
 *
 * <p>The frozen effective scope of a pin is therefore the meet of every path that reaches it: no wider than
 * the intersection the trusted port proved across all of them, content hash included, and when a later path
 * narrows it the descendants are revalidated under the narrower scope. A version is only re-entered when a
 * path proves a strictly narrower scope than every path before it, so the revalidation is bounded by the
 * finite set of scopes the trusted port can describe while the depth, distinct-version and complexity bounds
 * continue to bound the run as a whole. The result is never wider than the caller's own scope, which is the
 * security-critical property; the deterministic ordering of the traversal and of the frozen material is what
 * makes it reproducible.
 *
 * <p>The resolver is stateless and thread-safe; each call confines its state to a private run.
 */
public final class ReferenceGraphResolver {

    /** Resolves one document's reference candidates against the trusted context. */
    public ReferenceGraphOutcome resolve(
            List<ReferenceCandidate> candidates,
            TrustedExecutionContext context,
            AuthorizationService authorizationService,
            ReferenceValidationContext referenceContext) {
        if (candidates == null) {
            throw ReferenceException.invalid("A reference resolution requires its candidate list");
        }
        if (context == null) {
            throw ReferenceException.invalid("A reference resolution requires the trusted execution context");
        }
        if (referenceContext == null) {
            throw ReferenceException.invalid("A reference resolution requires the trusted reference context");
        }
        return new Run(candidates, context, authorizationService, referenceContext).execute();
    }

    /** One resolution run; all mutable state lives here so the resolver itself stays stateless. */
    private static final class Run {

        private final List<ReferenceCandidate> candidates;
        private final TrustedExecutionContext context;
        private final AuthorizationService authorizationService;
        private final ReferenceValidationContext referenceContext;

        /** Snapshots by full immutable pin, so two content hashes never share one catalog answer. */
        private final Map<String, AudienceVersionSnapshot> snapshotByPin = new HashMap<>();
        /** Pins the catalog cannot answer, so the same absent version is never asked for twice. */
        private final Set<String> missingPins = new HashSet<>();
        /** Pins refused for a reason that is a property of the pin, not of the path that reached it. */
        private final Set<String> refusedPins = new HashSet<>();
        /** Version identities on the current path: cycle semantics intentionally mean one logical identity. */
        private final Set<String> onPath = new LinkedHashSet<>();
        /** Where each already-resolved exact pin lives in {@link #resolved}. */
        private final Map<String, Integer> indexByPin = new LinkedHashMap<>();
        private final List<ResolvedAudienceReference> resolved = new ArrayList<>();
        private final List<ReferenceGraphIssue> issues = new ArrayList<>();
        private final Set<String> issuedCodes = new LinkedHashSet<>();

        private EffectiveDataScope callerScope;
        private boolean callerScopeChecked;
        private long expandedComplexity;
        private boolean halted;

        private Run(
                List<ReferenceCandidate> candidates,
                TrustedExecutionContext context,
                AuthorizationService authorizationService,
                ReferenceValidationContext referenceContext) {
            this.candidates = List.copyOf(candidates);
            this.context = context;
            this.authorizationService = authorizationService;
            this.referenceContext = referenceContext;
        }

        ReferenceGraphOutcome execute() {
            if (candidates.isEmpty()) {
                return new ReferenceGraphOutcome(ReferenceGraph.of(List.of()), List.of());
            }
            // The document under edit is itself the version it is about to become, so when the host knows that
            // identity it belongs on every path: a reference that reaches it would make the produced version
            // depend on itself, which is exactly the cycle a candidate edit must not be able to create.
            seedCandidateRootOnEveryPath();
            checkCandidateScopes();
            for (AudienceVersionRef direct : distinctDirectPins()) {
                visit(direct, firstNodeOf(direct), 1, null, null);
            }
            ReferenceGraph graph = halted
                    ? ReferenceGraph.of(List.of())
                    : ReferenceGraph.of(resolved);
            return new ReferenceGraphOutcome(graph, List.copyOf(issues));
        }

        // ---------------------------------------------------------------- setup

        /** Puts the identity of the version being edited at the bottom of every traversal path. */
        private void seedCandidateRootOnEveryPath() {
            AudienceVersionRef root = referenceContext.candidateRootVersion();
            if (root != null) {
                onPath.add(root.identityKey());
            }
        }

        /**
         * Checks each site's subject scope against the resolved version independently of dependency accounting.
         *
         * <p>This runs over every candidate rather than over distinct pins, because the subject fact is a
         * property of the site: the same version used once on the root subject and once on a related subject
         * must be refused at the second site even though the pin is accounted for only once. The lookup is by
         * exact pin, so two sites naming one identity with different bytes are judged against their own
         * catalog answers.
         */
        private void checkCandidateScopes() {
            for (ReferenceCandidate candidate : candidates) {
                AudienceVersionSnapshot snapshot = lookup(candidate.reference());
                if (snapshot != null && snapshot.subject() != candidate.scopeSubject()) {
                    issue(ReferenceErrorCodes.SUBJECT_MISMATCH,
                            "The referenced audience version is defined over a different subject than this scope.",
                            candidate.nodeId(), 1);
                }
            }
        }

        /** The distinct directly referenced pins, in canonical exact-pin order. */
        private List<AudienceVersionRef> distinctDirectPins() {
            Map<String, AudienceVersionRef> byPin = new LinkedHashMap<>();
            for (ReferenceCandidate candidate : candidates) {
                byPin.putIfAbsent(candidate.reference().pinKey(), candidate.reference());
            }
            List<AudienceVersionRef> pins = new ArrayList<>(byPin.values());
            pins.sort(AudienceVersionRef.ORDER);
            return pins;
        }

        /** The first site in declaration order that pinned this exact version, used to attribute findings. */
        private String firstNodeOf(AudienceVersionRef reference) {
            String pin = reference.pinKey();
            for (ReferenceCandidate candidate : candidates) {
                if (candidate.reference().pinKey().equals(pin)) {
                    return candidate.nodeId();
                }
            }
            // Unreachable: the pin came from this candidate list, so at least one site names it.
            throw ReferenceException.invalid("A direct reference must be pinned by at least one site");
        }

        // ---------------------------------------------------------------- traversal

        /**
         * Validates one incoming path to one exact pin and, when it is usable, descends into its dependencies.
         *
         * <p>The order is fixed and deliberate: the cycle check first, because a version that depends on
         * itself is not a version to resolve; then a pin already refused for a reason of its own, which no
         * other path can change; then the depth bound; then the pin's own catalog facts; then the facts that
         * belong to <em>this path</em> — the parent subject and the trusted scope intersection — which are
         * re-proved on every incoming path rather than remembered from the first one; and only then the
         * once-per-pin accounting and the descent into the version's dependencies.
         *
         * <p>A path that proves an empty intersection is refused like any other failure, and because no scope
         * is narrower than the empty one, the version can no longer be frozen under any effective scope at
         * all: the outcome carries the finding and is not clean, so no caller may accept the graph it holds.
         */
        private void visit(
                AudienceVersionRef reference,
                String nodeId,
                int depth,
                EffectiveDataScope parentScope,
                SubjectKind parentSubject) {
            if (halted) {
                return;
            }
            String identity = reference.identityKey();
            String pin = reference.pinKey();
            if (onPath.contains(identity)) {
                issue(ReferenceErrorCodes.CYCLE_DETECTED,
                        "The reference graph is cyclic: this version depends on itself.", nodeId, depth);
                return;
            }
            if (refusedPins.contains(pin)) {
                // This pin already failed for a reason that belongs to the pin itself, not to the path it was
                // reached by, so no later path can reach a different answer.
                return;
            }
            if (depth > referenceContext.policy().maxDepth()) {
                issue(ReferenceErrorCodes.DEPTH_EXCEEDED,
                        "The transitive reference depth exceeds the configured maximum.", nodeId, depth);
                return;
            }

            AudienceVersionSnapshot snapshot = lookup(reference);
            if (snapshot == null) {
                refusedPins.add(pin);
                issue(ReferenceErrorCodes.VERSION_NOT_FOUND,
                        "The referenced audience version is not available to this caller.", nodeId, depth);
                return;
            }
            Refusal refusal = refusal(reference, snapshot);
            if (refusal != null) {
                refusedPins.add(pin);
                issue(refusal.code(), refusal.message(), nodeId, depth);
                return;
            }
            if (parentSubject != null && snapshot.subject() != parentSubject) {
                // Path-dependent: the same version may be perfectly usable under a matching parent, so this is
                // re-proved on every incoming path instead of being remembered against the pin.
                issue(ReferenceErrorCodes.SUBJECT_MISMATCH,
                        "A referenced version is defined over a different subject than the version depending on it.",
                        nodeId, depth);
                return;
            }
            EffectiveDataScope effective = intersectScope(snapshot, parentScope, nodeId, depth);
            if (effective == null) {
                return;
            }

            Integer existing = indexByPin.get(pin);
            if (existing != null) {
                EffectiveDataScope proved = meet(existing, effective, nodeId, depth);
                if (proved == null || proved.equals(resolved.get(existing).effectiveDataScope())) {
                    // This path can prove nothing narrower than every earlier path already proved.
                    return;
                }
                ResolvedAudienceReference prior = resolved.get(existing);
                resolved.set(existing,
                        new ResolvedAudienceReference(reference, prior.snapshot(), proved, prior.depth()));
                effective = proved;
            } else {
                if (resolved.size() >= referenceContext.policy().maxDistinctVersions()) {
                    issue(ReferenceErrorCodes.DISTINCT_VERSIONS_EXCEEDED,
                            "The number of distinct referenced versions exceeds the configured maximum.",
                            nodeId, depth);
                    halted = true;
                    return;
                }
                // Accumulated in a long so a hostile catalog cannot overflow the sum into a passing value.
                expandedComplexity += snapshot.expandedComplexity();
                if (expandedComplexity > referenceContext.policy().maxExpandedComplexity()) {
                    issue(ReferenceErrorCodes.EXPANDED_COMPLEXITY_EXCEEDED,
                            "The expanded complexity of the referenced versions exceeds the configured maximum.",
                            nodeId, depth);
                    halted = true;
                    return;
                }
                indexByPin.put(pin, resolved.size());
                resolved.add(new ResolvedAudienceReference(reference, snapshot, effective, depth));
            }

            onPath.add(identity);
            for (AudienceVersionRef dependency : snapshot.directDependencies()) {
                visit(dependency, nodeId, depth + 1, effective, snapshot.subject());
            }
            onPath.remove(identity);
        }

        /** A pin-level refusal: a failure that is a property of the pin, not of the path that reached it. */
        private record Refusal(String code, String message) {
        }

        /**
         * The reason this exact pin can never be used by this caller, or {@code null} when it can.
         *
         * <p>Every one of these facts is a property of the catalog material and the trusted context alone, so a
         * second path to the pin cannot change the answer and the finding is never repeated for another path.
         */
        private Refusal refusal(AudienceVersionRef reference, AudienceVersionSnapshot snapshot) {
            if (!snapshot.version().equals(reference)) {
                // The catalog answered with a version whose exact bytes or identity differ from the pin.
                return new Refusal(ReferenceErrorCodes.VERSION_CONFLICT,
                        "The pinned version identity resolves to different content than the pin declares.");
            }
            if (!snapshot.tenantId().equals(context.tenantId())) {
                // A cross-tenant and an absent version deliberately share one non-enumerating finding.
                return new Refusal(ReferenceErrorCodes.VERSION_NOT_FOUND,
                        "The referenced audience version is not available to this caller.");
            }
            if (!snapshot.availability().isUsable()) {
                return new Refusal(ReferenceErrorCodes.VERSION_UNAVAILABLE,
                        "The referenced audience version is not currently available.");
            }
            if (!referenceContext.programPolicy()
                    .allowsProgramRelation(context.programId(), snapshot.programId())) {
                return new Refusal(ReferenceErrorCodes.PROGRAM_NOT_ALLOWED,
                        "The referenced audience version belongs to a program this caller may not reference.");
            }
            if (!authorized(snapshot)) {
                return new Refusal(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE,
                        "The referenced segment is not accessible to this caller.");
            }
            return null;
        }

        // ---------------------------------------------------------------- trusted ports

        /** The exact-pin catalog answer, asked at most once per full immutable pin. */
        private AudienceVersionSnapshot lookup(AudienceVersionRef reference) {
            String pin = reference.pinKey();
            AudienceVersionSnapshot cached = snapshotByPin.get(pin);
            if (cached != null) {
                return cached;
            }
            if (missingPins.contains(pin)) {
                return null;
            }
            Optional<AudienceVersionSnapshot> found;
            try {
                found = referenceContext.catalog().findExact(reference);
            } catch (RuntimeException exception) {
                // A failure of the trusted catalog is an unavailable answer, never a permissive one.
                missingPins.add(pin);
                return null;
            }
            if (found == null || found.isEmpty()) {
                missingPins.add(pin);
                return null;
            }
            snapshotByPin.put(pin, found.get());
            return found.get();
        }

        /**
         * The reachability decision for one resolved version, accepted only when it is exactly the decision
         * this reference asked for.
         *
         * <p>An allowed decision is not enough: the boundary must say allowed in both its disposition and its
         * reason, must have decided about a {@code SEGMENT}, and must have required exactly the permission a
         * {@code SEGMENT} requires. A permissive-looking decision about anything else — a weaker resource kind,
         * a weaker required permission, a stray reason, a null or partial record — is not evidence that this
         * caller may reach this segment, so it fails closed exactly like a thrown or absent decision.
         */
        private boolean authorized(AudienceVersionSnapshot snapshot) {
            if (authorizationService == null) {
                return false;
            }
            try {
                AuthorizationDecision decision = authorizationService.authorize(AuthorizationRequest.of(
                        context,
                        ResourceKind.SEGMENT,
                        snapshot.version().audienceId(),
                        snapshot.tenantId(),
                        snapshot.programId()));
                return decision != null
                        && decision.disposition() == AuthorizationDisposition.ALLOWED
                        && decision.allowed()
                        && decision.reason() == AuthorizationReason.ALLOWED
                        && decision.resourceKind() == ResourceKind.SEGMENT
                        && decision.requiredPermission() == ResourceKind.SEGMENT.requiredPermission();
            } catch (RuntimeException exception) {
                // Any failure of the boundary, including a thrown cause, is an unavailable decision.
                return false;
            }
        }

        /** The trusted caller scope, resolved once per run; null when it cannot be proven. */
        private EffectiveDataScope callerScope(String nodeId, int depth) {
            if (!callerScopeChecked) {
                callerScopeChecked = true;
                EffectiveDataScope proven = null;
                try {
                    Optional<EffectiveDataScope> found = referenceContext.dataScopePort().callerScope(
                            context.tenantId(), context.programId(), context.userId(), context.dataScopeId());
                    if (found != null && found.isPresent()
                            && found.get().dataScopeId().equals(context.dataScopeId())) {
                        proven = found.get();
                    }
                } catch (RuntimeException exception) {
                    proven = null;
                }
                callerScope = proven;
            }
            if (callerScope == null) {
                issue(ReferenceErrorCodes.DATASCOPE_UNAVAILABLE,
                        "The trusted effective DataScope of this caller could not be established.", nodeId, depth);
            }
            return callerScope;
        }

        private EffectiveDataScope intersectScope(
                AudienceVersionSnapshot snapshot, EffectiveDataScope parentScope, String nodeId, int depth) {
            EffectiveDataScope caller = callerScope(nodeId, depth);
            if (caller == null) {
                return null;
            }
            EffectiveDataScope parent = parentScope == null ? caller : parentScope;
            return intersect(caller, parent, snapshot.effectiveDataScope(), nodeId, depth);
        }

        /**
         * The scope every path to one pin has proved so far, intersected with what the current path proves.
         *
         * <p>It is the meet of two already-proved effective scopes, so the frozen scope of a pin can only ever
         * narrow as more paths reach it — never widen, and never quietly keep an earlier, wider scope because
         * an earlier path was accepted first. When a path can prove no narrower scope than the frozen one, its
         * own scope is returned unchanged and the caller recognises the equality and stops.
         */
        private EffectiveDataScope meet(
                int index, EffectiveDataScope candidate, String nodeId, int depth) {
            EffectiveDataScope caller = callerScope(nodeId, depth);
            if (caller == null) {
                return null;
            }
            return intersect(caller, resolved.get(index).effectiveDataScope(), candidate, nodeId, depth);
        }

        /** One trusted three-way intersection, or null after recording why it could not be proved. */
        private EffectiveDataScope intersect(
                EffectiveDataScope caller,
                EffectiveDataScope parent,
                EffectiveDataScope referenced,
                String nodeId,
                int depth) {
            Optional<EffectiveDataScope> effective;
            try {
                effective = referenceContext.dataScopePort().intersect(caller, parent, referenced);
            } catch (RuntimeException exception) {
                issue(ReferenceErrorCodes.DATASCOPE_UNAVAILABLE,
                        "The trusted effective DataScope intersection could not be computed.", nodeId, depth);
                return null;
            }
            if (effective == null || effective.isEmpty()) {
                issue(ReferenceErrorCodes.DATASCOPE_INTERSECTION_EMPTY,
                        "The trusted effective DataScope intersection of this reference is empty.", nodeId, depth);
                return null;
            }
            return effective.get();
        }

        // ---------------------------------------------------------------- findings

        /**
         * Records one finding, collapsing a repeat of the same code on the same site.
         *
         * <p>Collapsing keeps the outward list bounded and non-enumerating: a document that references many
         * versions a caller may not see still produces one finding per site rather than one per version, and no
         * finding ever names a version, a tenant or a scope.
         */
        private void issue(String code, String message, String nodeId, int depth) {
            if (!issuedCodes.add(code + "|" + nodeId)) {
                return;
            }
            issues.add(new ReferenceGraphIssue(code, message, nodeId, depth));
        }
    }
}