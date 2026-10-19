package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.gate.ReferenceReadinessInputs;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one adapter from the committed reference corpus to the dedicated reference Gate inputs.
 *
 * <p>It binds the corpus identity, the corpus revision and the corpus content hash to the dedicated
 * {@code segment.existing-audience-reference} capability's frozen input bundle, together with the reference
 * policy hash, the catalog/directory contract hash and the dependency-graph inputs hash that the corpus's own
 * fixtures define. Every value is derived from the loaded corpus, so any change to a case, an expectation, a
 * review ledger, a fixture payload or the fixture catalog moves the canonical current-input hash and the
 * previous deterministic evidence stops covering this revision.
 *
 * <p>The corpus's content hash is the manifest hash, and that is not a shortcut: the manifest pins the content
 * hash of every case, expectation, review ledger and fixture payload, so its hash transitively covers the
 * whole corpus. Hashing the files again here would produce a second spelling of the same fact and a second
 * thing to keep in step.
 *
 * <p>Nothing here activates anything. The inputs this adapter produces feed
 * {@link com.company.campaign.evaluation.segment.gate.ReferenceReadinessProjector}, which promotes the
 * capability only for a genuine approved {@code PASSED} decision over exactly these inputs; with no such
 * decision the projection stays {@code EXPERIMENTAL}, and with a stale one it stays {@code EVALUATION_REQUIRED}.
 */
public final class ReferenceCorpusReadiness {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private ReferenceCorpusReadiness() {
    }

    /** The dedicated reference Gate inputs the committed corpus defines. */
    public static ReferenceReadinessInputs inputs(ReferenceCorpusBundle bundle) {
        if (bundle == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EVALUATION_INPUT_INVALID,
                    "Reference corpus readiness requires a loaded reference corpus");
        }
        return ReferenceReadinessInputs.current(
                ReferenceCorpusVersions.CORPUS_ID,
                ReferenceCorpusVersions.CORPUS_VERSION,
                bundle.manifest().manifestHash(),
                referencePolicyHash(bundle),
                catalogDirectoryContractHash(bundle),
                dependencyGraphInputsHash(bundle));
    }

    /**
     * The hash of every reference policy the corpus declares.
     *
     * <p>It is derived from the pinned payload hashes of the policy fixtures rather than from their parsed
     * contents, so a policy change that keeps the bytes identical is a no-op and any byte change is drift.
     */
    public static ContentHash referencePolicyHash(ReferenceCorpusBundle bundle) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("referencePolicyIdentity", ReferenceCorpusVersions.FIXTURE_CATALOG_VERSION);
        material.put("policies", fixtureHashesFor(bundle, ReferenceFixtureRole.REFERENCE_POLICY));
        return CANONICAL_JSON.hash(material);
    }

    /** The hash of the catalog/directory contract identity this corpus was reviewed against. */
    public static ContentHash catalogDirectoryContractHash(ReferenceCorpusBundle bundle) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("contractVersion", ReferenceCorpusVersions.CATALOG_DIRECTORY_CONTRACT_VERSION);
        material.put("fixtureCatalogVersion", bundle.fixtureCatalog().catalogVersion());
        material.put("fixtureCatalogHash", bundle.manifest().fixtureCatalog().contentHash().value());
        return CANONICAL_JSON.hash(material);
    }

    /**
     * The hash of the dependency-graph inputs: every exact-version catalog the corpus resolves against.
     *
     * <p>It is what makes "the graph inputs changed" observable separately from "a case changed": a version
     * added to a catalog fixture, or a catalog payload edited, moves this hash without touching the manifest.
     */
    public static ContentHash dependencyGraphInputsHash(ReferenceCorpusBundle bundle) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("inputsVersion", ReferenceCorpusVersions.DEPENDENCY_GRAPH_INPUTS_VERSION);
        material.put("catalogs", fixtureHashesFor(bundle, ReferenceFixtureRole.VERSION_CATALOG));
        return CANONICAL_JSON.hash(material);
    }

    private static List<Map<String, Object>> fixtureHashesFor(
            ReferenceCorpusBundle bundle, ReferenceFixtureRole role) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ReferenceFixtureDescriptor fixture : bundle.fixtureCatalog().fixtures()) {
            if (fixture.role() != role) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fixtureId", fixture.fixtureId());
            entry.put("fixtureVersion", fixture.fixtureVersion());
            entry.put("payloadHash", fixture.payloadHash().value());
            entries.add(entry);
        }
        entries.sort(java.util.Comparator.comparing(entry -> (String) entry.get("fixtureId")));
        return List.copyOf(entries);
    }
}