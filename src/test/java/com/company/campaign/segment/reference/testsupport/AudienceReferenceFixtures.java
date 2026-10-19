package com.company.campaign.segment.reference.testsupport;

import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceReferenceDirectory;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic T18 audience-reference discovery fixtures.
 *
 * <p>They build only accepted public contracts from fixed text, fixed pins and fixed instants, so the same
 * directory state, candidate set and evidence hash are reproduced byte for byte on every run.
 */
public final class AudienceReferenceFixtures {

    /** The one audience the standard fixtures reference. */
    public static final String AUDIENCE_VIP = "audience-vip";

    /** A second audience that shares the standard natural-language name. */
    public static final String AUDIENCE_GOLD = "audience-gold";

    /** The natural-language name both standard audiences answer to. */
    public static final String SHARED_NAME = "高价值客户人群";

    /** The name only the VIP audience answers to. */
    public static final String VIP_NAME = "VIP高价值人群";

    /** The dynamic-but-nameable phrase the VIP audience also answers to. */
    public static final String DYNAMIC_NAMED_PHRASE = "最新VIP高价值人群";

    /** The single published version of the standard audience. */
    public static final String VERSION_SEVEN = "version-7";

    /** An earlier published version of the standard audience. */
    public static final String VERSION_SIX = "version-6";

    /** The fixed publication instant every standard version choice carries. */
    public static final Instant PUBLISHED_AT = Instant.parse("2026-09-01T00:00:00Z");

    /** A later publication instant, used to prove nothing keys on publication order. */
    public static final Instant LATER_PUBLISHED_AT = Instant.parse("2026-09-15T00:00:00Z");

    private AudienceReferenceFixtures() {
    }

    /** An exact pin of the standard audience with a hash derived only from its own identity text. */
    public static AudienceVersionRef pin(String audienceId, String versionId) {
        return SegmentReferenceFixtures.ref(audienceId, versionId);
    }

    /** One published version choice of the standard audience. */
    public static AudienceVersionChoiceFixture choice(String audienceId, String versionId, Instant publishedAt) {
        return new AudienceVersionChoiceFixture(pin(audienceId, versionId), publishedAt);
    }

    /** The one published version of one audience, as a directory entry. */
    public record AudienceVersionChoiceFixture(AudienceVersionRef reference, Instant publishedAt) {

        /** The safe label this version choice offers, built only from its immutable pin parts. */
        public String label() {
            return reference.versionId() + " (published " + publishedAt + ")";
        }
    }

    // ------------------------------------------------------------------ standard directories

    /** One audience, one published version: the unique-pin case. */
    public static InMemoryAudienceReferenceDirectory singleVersionDirectory() {
        InMemoryAudienceReferenceDirectory directory = new InMemoryAudienceReferenceDirectory();
        directory.registerAudience(AUDIENCE_VIP, VIP_NAME, List.of(SHARED_NAME, DYNAMIC_NAMED_PHRASE));
        AudienceVersionChoiceFixture version = choice(AUDIENCE_VIP, VERSION_SEVEN, PUBLISHED_AT);
        directory.publishVersion(version.reference(), version.publishedAt(), version.label());
        return directory;
    }

    /** One audience carrying only its earlier published version: the different-immutable-pin case. */
    public static InMemoryAudienceReferenceDirectory earlierVersionDirectory() {
        InMemoryAudienceReferenceDirectory directory = new InMemoryAudienceReferenceDirectory();
        directory.registerAudience(AUDIENCE_VIP, VIP_NAME, List.of(SHARED_NAME, DYNAMIC_NAMED_PHRASE));
        AudienceVersionChoiceFixture version = choice(AUDIENCE_VIP, VERSION_SIX, PUBLISHED_AT);
        directory.publishVersion(version.reference(), version.publishedAt(), version.label());
        return directory;
    }

    /** One audience, two published versions: the ambiguous-version case. */
    public static InMemoryAudienceReferenceDirectory twoVersionDirectory() {
        InMemoryAudienceReferenceDirectory directory = new InMemoryAudienceReferenceDirectory();
        directory.registerAudience(AUDIENCE_VIP, VIP_NAME, List.of(SHARED_NAME));
        AudienceVersionChoiceFixture older = choice(AUDIENCE_VIP, VERSION_SIX, PUBLISHED_AT);
        AudienceVersionChoiceFixture newer = choice(AUDIENCE_VIP, VERSION_SEVEN, LATER_PUBLISHED_AT);
        directory.publishVersion(older.reference(), older.publishedAt(), older.label());
        directory.publishVersion(newer.reference(), newer.publishedAt(), newer.label());
        return directory;
    }

    /** Two audiences that answer to the same name, each with one published version. */
    public static InMemoryAudienceReferenceDirectory twoAudienceDirectory() {
        InMemoryAudienceReferenceDirectory directory = new InMemoryAudienceReferenceDirectory();
        directory.registerAudience(AUDIENCE_VIP, VIP_NAME, List.of(SHARED_NAME));
        directory.registerAudience(AUDIENCE_GOLD, "黄金会员人群", List.of(SHARED_NAME));
        AudienceVersionChoiceFixture vip = choice(AUDIENCE_VIP, VERSION_SEVEN, PUBLISHED_AT);
        AudienceVersionChoiceFixture gold = choice(AUDIENCE_GOLD, VERSION_SEVEN, PUBLISHED_AT);
        directory.publishVersion(vip.reference(), vip.publishedAt(), vip.label());
        directory.publishVersion(gold.reference(), gold.publishedAt(), gold.label());
        return directory;
    }

    /** One audience that discovery surfaces but that may no longer be referenced. */
    public static InMemoryAudienceReferenceDirectory deniedDirectory() {
        return singleVersionDirectory().denyReference(AUDIENCE_VIP);
    }

    /** One audience that is visible and has no eligible published version at all. */
    public static InMemoryAudienceReferenceDirectory unpublishedDirectory() {
        InMemoryAudienceReferenceDirectory directory = new InMemoryAudienceReferenceDirectory();
        directory.registerAudience(AUDIENCE_VIP, VIP_NAME, List.of(SHARED_NAME));
        return directory;
    }

    /** A directory that knows nothing about the standard name. */
    public static InMemoryAudienceReferenceDirectory emptyDirectory() {
        return new InMemoryAudienceReferenceDirectory();
    }

    /** The host-owned context of one directory state. */
    public static AudienceReferenceDirectoryContext context(InMemoryAudienceReferenceDirectory directory) {
        return directory.context();
    }
}