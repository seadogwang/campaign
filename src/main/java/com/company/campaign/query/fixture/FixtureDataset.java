package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, hash-pinned deterministic fixture dataset.
 *
 * <p>It is a strict value: every pin comes from the reviewed descriptor and the payload's own exact bytes, and
 * every collection is defensively copied and deterministically ordered. A dataset carries no wall clock, no
 * JVM default timezone, no locale default and no ambient tenant context, so the same file always evaluates to
 * the same member set.
 *
 * <p>It has no public constructor: {@link FixtureDatasetLoader} is the only producer, so a dataset can only
 * exist after real-path containment, exact-byte hash verification and strict parsing have all passed.
 */
public final class FixtureDataset {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String fixtureId;
    private final String fixtureVersion;
    private final FixtureRole role;
    private final String dataset;
    private final Instant asOf;
    private final ZoneId timezone;
    private final Instant dataWatermark;
    private final MemberIdSet memberUniverse;
    private final ContentHash payloadHash;
    private final List<String> currencies;
    private final List<String> statuses;
    private final List<DatasetRow.MemberRow> members;
    private final List<DatasetRow.OrderRow> orders;
    private final List<DatasetRow.EventRow> events;
    private final List<RefundRow> refunds;
    private final ContentHash datasetHash;

    FixtureDataset(
            String fixtureId,
            String fixtureVersion,
            FixtureRole role,
            String dataset,
            Instant asOf,
            ZoneId timezone,
            Instant dataWatermark,
            MemberIdSet memberUniverse,
            ContentHash payloadHash,
            List<String> currencies,
            List<String> statuses,
            List<DatasetRow.MemberRow> members,
            List<DatasetRow.OrderRow> orders,
            List<DatasetRow.EventRow> events,
            List<RefundRow> refunds) {
        this.fixtureId = requireText(fixtureId, "fixtureId");
        this.fixtureVersion = requireText(fixtureVersion, "fixtureVersion");
        this.role = requirePresent(role, "fixtureRole");
        this.dataset = requireText(dataset, "fixtureDataset");
        this.asOf = requirePresent(asOf, "fixtureAsOf");
        this.timezone = requirePresent(timezone, "fixtureTimezone");
        this.dataWatermark = requirePresent(dataWatermark, "fixtureDataWatermark");
        this.memberUniverse = requirePresent(memberUniverse, "fixtureMemberUniverse");
        this.payloadHash = requirePresent(payloadHash, "fixturePayloadHash");
        this.currencies = List.copyOf(requirePresent(currencies, "fixtureCurrencies"));
        this.statuses = List.copyOf(requirePresent(statuses, "fixtureStatuses"));
        this.members = List.copyOf(requirePresent(members, "fixtureMembers"));
        this.orders = List.copyOf(requirePresent(orders, "fixtureOrders"));
        this.events = List.copyOf(requirePresent(events, "fixtureEvents"));
        this.refunds = List.copyOf(requirePresent(refunds, "fixtureRefunds"));
        this.datasetHash = CANONICAL_JSON.hash(material());
    }

    /** The reviewed fixture id this dataset was loaded for. */
    public String fixtureId() {
        return fixtureId;
    }

    /** The reviewed fixture version this dataset was loaded for. */
    public String fixtureVersion() {
        return fixtureVersion;
    }

    /** The reviewed fixture role. */
    public FixtureRole role() {
        return role;
    }

    /** The payload's declared dataset name. */
    public String dataset() {
        return dataset;
    }

    /** The fixture's fixed evaluation instant. */
    public Instant asOf() {
        return asOf;
    }

    /** The fixture's fixed timezone. */
    public ZoneId timezone() {
        return timezone;
    }

    /** The fixture's fixed data watermark. */
    public Instant dataWatermark() {
        return dataWatermark;
    }

    /** The descriptor's declared member universe. */
    public MemberIdSet memberUniverse() {
        return memberUniverse;
    }

    /** The SHA-256 of the exact payload bytes this dataset was parsed from. */
    public ContentHash payloadHash() {
        return payloadHash;
    }

    /** The payload's declared currency vocabulary. */
    public List<String> currencies() {
        return currencies;
    }

    /** The payload's declared status vocabulary. */
    public List<String> statuses() {
        return statuses;
    }

    /** Member rows in declared payload order. */
    public List<DatasetRow.MemberRow> members() {
        return members;
    }

    /** Order rows in declared payload order. */
    public List<DatasetRow.OrderRow> orders() {
        return orders;
    }

    /** Event rows in declared payload order. */
    public List<DatasetRow.EventRow> events() {
        return events;
    }

    /** Refund rows in declared payload order. */
    public List<RefundRow> refunds() {
        return refunds;
    }

    /** The canonical content hash of every pinned fact this dataset carries. */
    public ContentHash datasetHash() {
        return datasetHash;
    }

    /** The rows of one subject entity, in declared order; an unmapped subject is an empty list. */
    List<DatasetRow> rowsOf(SubjectKind entity) {
        return switch (entity) {
            case MEMBER -> List.copyOf(members);
            case ORDER -> List.copyOf(orders);
            case EVENT -> List.copyOf(events);
            case ACCOUNT -> List.of();
        };
    }

    private Material material() {
        return new Material(
                fixtureId, fixtureVersion, role.name(), dataset, asOf.toString(), timezone.getId(),
                dataWatermark.toString(), memberUniverse.memberIds(), payloadHash.value(),
                currencies, statuses, members, orders, events, refunds);
    }

    private static String requireText(String value, String invariant) {
        if (value == null || value.isBlank()) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_RESULT_INVALID, invariant,
                    "A fixture dataset requires a non-blank bounded value.");
        }
        return value;
    }

    private static <T> T requirePresent(T value, String invariant) {
        if (value == null) {
            throw new FixtureException(FixtureErrorCodes.FIXTURE_RESULT_INVALID, invariant,
                    "A fixture dataset requires a non-null value.");
        }
        return value;
    }

    /** The exact material the dataset hash covers; deliberately includes the payload hash, not the file path. */
    private record Material(
            String fixtureId,
            String fixtureVersion,
            String role,
            String dataset,
            String asOf,
            String timezone,
            String dataWatermark,
            List<String> memberUniverse,
            String payloadHash,
            List<String> currencies,
            List<String> statuses,
            List<DatasetRow.MemberRow> members,
            List<DatasetRow.OrderRow> orders,
            List<DatasetRow.EventRow> events,
            List<RefundRow> refunds) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FixtureDataset dataset && Objects.equals(datasetHash, dataset.datasetHash);
    }

    @Override
    public int hashCode() {
        return datasetHash.hashCode();
    }

    @Override
    public String toString() {
        return "FixtureDataset[fixtureId=" + fixtureId + ", datasetHash=" + datasetHash + ']';
    }
}