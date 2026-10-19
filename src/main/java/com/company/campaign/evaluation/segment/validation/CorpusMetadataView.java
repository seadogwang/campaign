package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The parsed T04 Metadata snapshots of one corpus, with the findings that parsing produced.
 *
 * <p>This is the only evaluation-side place that reads the payload as a T04 snapshot. The contract and the
 * loader keep Metadata opaque, exactly as T03 designed them; interpreting the bytes is T06's validation
 * concern, because a candidate corpus has to be built against a real T04 snapshot for its DSL bindings and
 * candidate references to mean anything.
 *
 * <p>A payload that does not parse is not fatal: it is recorded as a finding and left out of the view, so
 * the accepted non-Gate example still loads and the report says why it is not a candidate.
 */
final class CorpusMetadataView {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final Map<String, MetadataSnapshot> snapshots;
    private final Map<String, Set<String>> definitionIds;
    private final List<Issue> issues;

    private CorpusMetadataView(
            Map<String, MetadataSnapshot> snapshots, Map<String, Set<String>> definitionIds, List<Issue> issues) {
        this.snapshots = snapshots;
        this.definitionIds = definitionIds;
        this.issues = issues;
    }

    /** Parses every Metadata payload in the bundle and collects the findings. */
    static CorpusMetadataView of(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        Map<String, MetadataSnapshot> snapshots = new HashMap<>();
        Map<String, Set<String>> definitionIds = new HashMap<>();
        List<Issue> issues = new ArrayList<>();

        for (MetadataSnapshotDescriptor descriptor : bundle.metadataCatalog().snapshots()) {
            String path = "/metadata/snapshots/" + descriptor.snapshotId();
            byte[] bytes = bundle.metadataPayload(descriptor.snapshotId());
            if (bytes == null) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.METADATA_PAYLOAD_INVALID, path,
                        "Metadata payload for snapshot '" + descriptor.snapshotId()
                                + "' was not loaded as bytes"));
                continue;
            }
            MetadataSnapshot snapshot;
            try {
                snapshot = CANONICAL_JSON.read(new String(bytes, StandardCharsets.UTF_8), MetadataSnapshot.class);
            } catch (ContractException exception) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.METADATA_PAYLOAD_INVALID, path,
                        "Metadata payload for snapshot '" + descriptor.snapshotId()
                                + "' is not a valid T04 Metadata snapshot"));
                continue;
            }
            if (!snapshot.snapshotVersion().equals(descriptor.snapshotVersion())) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.METADATA_PAYLOAD_IDENTITY_MISMATCH, path,
                        "Metadata payload declares version '" + snapshot.snapshotVersion()
                                + "' but the catalog pins '" + descriptor.snapshotVersion() + "'"));
            }
            // The descriptor pins the payload file's bytes. Requiring those bytes to equal the canonical
            // serialization of the parsed snapshot proves the reviewed file and the reviewed value agree,
            // and that the hash the T05 binding pins is code-derived rather than hand-authored.
            if (!ContentHash.sha256(CANONICAL_JSON.write(snapshot)).equals(descriptor.contentHash())) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.METADATA_PAYLOAD_INVALID, path,
                        "Metadata payload for snapshot '" + descriptor.snapshotId()
                                + "' is not stored as the canonical serialization of its value"));
            }
            snapshots.put(descriptor.snapshotId(), snapshot);
            definitionIds.put(descriptor.snapshotId(), collectDefinitionIds(snapshot));
        }
        return new CorpusMetadataView(snapshots, definitionIds, issues);
    }

    /** The parsed snapshot with the given id, or {@code null} when the payload did not parse. */
    MetadataSnapshot snapshot(String snapshotId) {
        return snapshots.get(snapshotId);
    }

    /** Every attribute, metric and relation id the snapshot declares, or {@code null} when unresolved. */
    Set<String> definitionIds(String snapshotId) {
        Set<String> ids = definitionIds.get(snapshotId);
        return ids == null ? null : Set.copyOf(ids);
    }

    /** Findings from parsing and binding the payloads, in catalog order. */
    List<Issue> issues() {
        return issues;
    }

    private static Set<String> collectDefinitionIds(MetadataSnapshot snapshot) {
        Set<String> ids = new HashSet<>();
        for (MetadataKind kind : MetadataKind.values()) {
            for (MetadataDefinition definition : snapshot.definitions(kind)) {
                ids.add(definition.id());
            }
        }
        return ids;
    }
}