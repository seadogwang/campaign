package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;

import java.util.Objects;
import java.util.Set;

/**
 * The exact Proposal wire schema versions this build can read and write.
 *
 * <p>T31 added the optional top-level {@code journeyTopology} member to the strict
 * {@link CampaignProposalPatch} shape. Reading that member under the already-published Proposal version
 * {@code 1.3} would silently reinterpret an accepted strict version, so the evolution is made explicit
 * instead: {@code 1.3} stays readable only for the exact pre-T31 shape with no {@code journeyTopology} member
 * at all, and {@code 1.4} is the version that can carry the member.
 *
 * <p>This is a Proposal-specific boundary: the global Segment/tool envelope contract version, the Segment DSL
 * version pins and every other contract keep their own accepted versions. {@link ContractVersions#CURRENT} is
 * deliberately <em>not</em> advanced to {@code 1.4}.
 *
 * <p>Topology-aware writing stamps {@link #CURRENT}; topology-free authoring may retain {@code 1.3} so
 * previously accepted canonical bytes and hashes remain unchanged. A per-version shape invariant is proven
 * both by the strict codec and by {@link CampaignProposalPatch}'s own constructor, so a directly constructed
 * model cannot bypass it.
 */
public final class ProposalSchemaVersions {

    /**
     * The published pre-T31 Proposal version {@code 1.3}.
     *
     * <p>It is readable only for the exact accepted shape, which has no {@code journeyTopology} member in any
     * form — including an explicit {@code null}. Its canonical bytes and hash are unchanged.
     */
    public static final SchemaVersion LEGACY_V1_3 = ContractVersions.V1_3;

    /** The current Proposal version {@code 1.4}, the only version that may carry a {@code journeyTopology}. */
    public static final SchemaVersion CURRENT_V1_4 = new SchemaVersion(1, 4);

    /** The version stamped onto newly produced topology-aware Proposals. */
    public static final SchemaVersion CURRENT = CURRENT_V1_4;

    /** Every Proposal version this build can read. */
    public static final Set<SchemaVersion> SUPPORTED = Set.of(LEGACY_V1_3, CURRENT_V1_4);

    private ProposalSchemaVersions() {
    }

    /** True when the version is the legacy, topology-free {@code 1.3} shape. */
    public static boolean isLegacy(SchemaVersion version) {
        return Objects.equals(LEGACY_V1_3, version);
    }

    /** True when the version is the current {@code 1.4} shape. */
    public static boolean isCurrent(SchemaVersion version) {
        return Objects.equals(CURRENT, version);
    }

    /** True when the version is one of the two readable Proposal versions. */
    public static boolean isSupported(SchemaVersion version) {
        return version != null && SUPPORTED.contains(version);
    }
}
