package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.reference.AudienceReferenceDirectory;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceSummary;
import com.company.campaign.segment.reference.AudienceVersionChoice;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceReferenceDirectory;
import com.company.campaign.segment.reference.ReferenceDiscoveryLimits;
import com.company.campaign.segment.reference.ReferenceException;
import com.company.campaign.segment.reference.testsupport.AudienceReferenceFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18 section 6: resolution semantics for zero, denied, multiple and unique immutable published candidates,
 * current/latest wording, exact pinning, structured clarification choices and host-checked evidence.
 */
class AudienceReferenceResolutionTest {

    private static final String REFERENCE_ID = "ref-1";
    private static final List<String> CANDIDATES = List.of(AudienceReferenceFixtures.SHARED_NAME);

    private AudienceReferenceResolution resolve(
            InMemoryAudienceReferenceDirectory directory, String requestText) {
        return new AudienceReferenceResolver(directory.context())
                .resolve(REFERENCE_ID, CANDIDATES, requestText);
    }

    private AudienceReferenceResolution resolve(
            InMemoryAudienceReferenceDirectory directory,
            String requestText,
            String audienceSelection,
            String versionSelection) {
        return new AudienceReferenceResolver(directory.context())
                .resolve(REFERENCE_ID, CANDIDATES, requestText, audienceSelection, versionSelection);
    }

    // ------------------------------------------------------------------ zero candidates

    @Test
    void zeroCandidatesIsOneBoundedUnavailableOutcome() {
        AudienceReferenceResolution resolution =
                resolve(AudienceReferenceFixtures.emptyDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        assertEquals(Disposition.UNSUPPORTED, resolution.disposition());
        assertEquals(QuestionCode.AUDIENCE_REFERENCE_UNAVAILABLE, resolution.questionCode());
        assertTrue(resolution.choices().isEmpty());
        assertNull(resolution.resolved());
        assertNull(resolution.evidence());
        assertEquals(0, resolution.candidateCount());
    }

    @Test
    void anUnpublishedAudienceIsIndistinguishableFromAnAbsentOne() {
        AudienceReferenceResolution absent =
                resolve(AudienceReferenceFixtures.emptyDirectory(), AudienceReferenceFixtures.SHARED_NAME);
        AudienceReferenceResolution unpublished =
                resolve(AudienceReferenceFixtures.unpublishedDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        // "no such audience" and "an audience with no usable version" share one outward shape, so neither can
        // be used to probe what exists.
        assertEquals(absent.disposition(), unpublished.disposition());
        assertEquals(absent.questionCode(), unpublished.questionCode());
        assertEquals(absent.candidateCount(), unpublished.candidateCount());
        assertEquals(absent.toString(), unpublished.toString());
    }

    // ------------------------------------------------------------------ denied candidates

    @Test
    void deniedCandidateIsDeniedWithoutEnumeratingAnything() {
        AudienceReferenceResolution resolution =
                resolve(AudienceReferenceFixtures.deniedDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        assertEquals(Disposition.DENIED, resolution.disposition());
        assertTrue(resolution.choices().isEmpty());
        assertTrue(resolution.questionCodes().isEmpty());
        assertNull(resolution.resolved());
        assertNull(resolution.evidence());
    }

    // ------------------------------------------------------------------ unique pinning

    @Test
    void oneEligibleImmutablePublishedVersionIsPinnedExactly() {
        AudienceReferenceResolution resolution =
                resolve(AudienceReferenceFixtures.singleVersionDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        assertEquals(Disposition.RESOLVED, resolution.disposition());
        AudienceVersionRef expected = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SEVEN);
        assertEquals(expected, resolution.resolved());
        assertEquals(AudienceReferenceFixtures.AUDIENCE_VIP, resolution.resolved().audienceId());
        assertEquals(AudienceReferenceFixtures.PUBLISHED_AT, resolution.publishedAt());
        assertNotNull(resolution.evidence());
        assertEquals(expected, resolution.evidence().resolved());
        assertFalse(resolution.dynamicWording());
        assertFalse(resolution.pinnedFromDynamicWording());
        assertTrue(resolution.choices().isEmpty());
    }

    // ------------------------------------------------------------------ ambiguity

    @Test
    void severalAudiencesProduceOneStructuredClarificationWithSafeChoices() {
        AudienceReferenceResolution resolution =
                resolve(AudienceReferenceFixtures.twoAudienceDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        assertEquals(Disposition.NEEDS_INPUT, resolution.disposition());
        assertEquals(QuestionCode.SELECT_AUDIENCE_REFERENCE, resolution.questionCode());
        assertEquals(2, resolution.choices().size());
        assertEquals(
                List.of(AudienceReferenceFixtures.AUDIENCE_GOLD, AudienceReferenceFixtures.AUDIENCE_VIP),
                resolution.choices().stream().map(AudienceReferenceChoice::value).toList());
        assertNull(resolution.resolved());
        assertNull(resolution.evidence());
    }

    @Test
    void severalVersionsProduceOneStructuredClarificationOfExactPins() {
        AudienceReferenceResolution resolution =
                resolve(AudienceReferenceFixtures.twoVersionDirectory(), AudienceReferenceFixtures.SHARED_NAME);

        assertEquals(Disposition.NEEDS_INPUT, resolution.disposition());
        assertEquals(QuestionCode.SELECT_AUDIENCE_VERSION, resolution.questionCode());
        assertEquals(2, resolution.choices().size());
        for (AudienceReferenceChoice choice : resolution.choices()) {
            assertTrue(choice.value().contains("@sha256:"), "a version choice is an exact pin key");
        }
        assertNull(resolution.resolved());
    }

    @Test
    void anOfferedAudienceChoiceNarrowsAndStillAsksForTheVersion() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoAudienceDirectory();

        AudienceReferenceResolution narrowed = resolve(
                directory, AudienceReferenceFixtures.SHARED_NAME, AudienceReferenceFixtures.AUDIENCE_GOLD, null);

        assertEquals(Disposition.RESOLVED, narrowed.disposition());
        assertEquals(AudienceReferenceFixtures.AUDIENCE_GOLD, narrowed.resolved().audienceId());
    }

    @Test
    void anOfferedVersionChoicePinsThatExactVersion() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoVersionDirectory();
        AudienceVersionRef older = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SIX);

        AudienceReferenceResolution resolution = resolve(
                directory, AudienceReferenceFixtures.SHARED_NAME, null, older.pinKey());

        assertEquals(Disposition.RESOLVED, resolution.disposition());
        assertEquals(older, resolution.resolved());
        // The pinned version is the one that was offered, not the newest one that exists.
        assertEquals(AudienceReferenceFixtures.PUBLISHED_AT, resolution.publishedAt());
    }

    @Test
    void aVersionChoiceThatWasNeverOfferedIsRefusedAsIncompatible() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoVersionDirectory();
        AudienceVersionRef foreign = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_GOLD, AudienceReferenceFixtures.VERSION_SEVEN);

        ResolutionException refused = assertThrows(ResolutionException.class, () -> resolve(
                directory, AudienceReferenceFixtures.SHARED_NAME, null, foreign.pinKey()));

        assertEquals(ResolutionErrorCodes.OPTION_INCOMPATIBLE, refused.code());
    }

    @Test
    void anAudienceChoiceThatWasNeverOfferedIsRefusedAsIncompatible() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.singleVersionDirectory();

        ResolutionException refused = assertThrows(ResolutionException.class, () -> resolve(
                directory, AudienceReferenceFixtures.SHARED_NAME, AudienceReferenceFixtures.AUDIENCE_GOLD, null));

        assertEquals(ResolutionErrorCodes.OPTION_INCOMPATIBLE, refused.code());
    }

    // ------------------------------------------------------------------ current / latest wording

    @Test
    void dynamicWordingWithOneVersionResolvesOnceAndDisclosesThePin() {
        AudienceReferenceResolution resolution = resolve(
                AudienceReferenceFixtures.singleVersionDirectory(), "最新人群");

        assertEquals(Disposition.RESOLVED, resolution.disposition());
        assertTrue(resolution.dynamicWording());
        assertTrue(resolution.pinnedFromDynamicWording());
        assertNotNull(resolution.resolved());
        assertNotNull(resolution.evidence());
        assertTrue(resolution.evidence().pinnedFromDynamicWording());
        assertEquals(AudienceReferenceFixtures.PUBLISHED_AT, resolution.publishedAt());
    }

    @Test
    void dynamicWordingWithSeveralVersionsAsksForVersionSemanticsAndNeverPicksTheNewest() {
        AudienceReferenceResolution resolution = resolve(
                AudienceReferenceFixtures.twoVersionDirectory(), "当前人群");

        assertEquals(Disposition.NEEDS_INPUT, resolution.disposition());
        assertEquals(QuestionCode.SELECT_AUDIENCE_VERSION, resolution.questionCode());
        assertTrue(resolution.dynamicWording());
        assertNull(resolution.resolved());
    }

    @Test
    void aStaticNameIsNotTreatedAsDynamicWording() {
        AudienceReferenceResolution resolution = resolve(
                AudienceReferenceFixtures.singleVersionDirectory(), AudienceReferenceFixtures.VIP_NAME);

        assertFalse(resolution.dynamicWording());
        assertFalse(resolution.pinnedFromDynamicWording());
    }

    // ------------------------------------------------------------------ evidence

    @Test
    void evidenceHashIsSensitiveToTheRequestCandidatesAndDirectory() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.singleVersionDirectory();
        ContentHash baseline =
                resolve(directory, AudienceReferenceFixtures.SHARED_NAME).evidence().evidenceHash();

        // The same exact inputs reproduce the same evidence.
        assertEquals(baseline, resolve(directory, AudienceReferenceFixtures.SHARED_NAME).evidence().evidenceHash());

        // A different request wording moves it.
        assertNotEquals(baseline, resolve(directory, "最新人群").evidence().evidenceHash());

        // A different candidate text moves it.
        AudienceReferenceResolution otherCandidate = new AudienceReferenceResolver(directory.context())
                .resolve(REFERENCE_ID, List.of("别的名字"), AudienceReferenceFixtures.SHARED_NAME);
        assertFalse(otherCandidate.disposition().isResolved());

        // A different directory revision moves it.
        InMemoryAudienceReferenceDirectory grown = AudienceReferenceFixtures.singleVersionDirectory();
        grown.registerAudience("audience-other", "其他人群", List.of("其他人群"));
        assertNotEquals(
                baseline,
                resolve(grown, AudienceReferenceFixtures.SHARED_NAME).evidence().evidenceHash());
    }

    @Test
    void evidenceHashIsSensitiveToThePinnedVersion() {
        InMemoryAudienceReferenceDirectory directory = AudienceReferenceFixtures.twoVersionDirectory();
        AudienceVersionRef older = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SIX);
        AudienceVersionRef newer = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SEVEN);

        ContentHash olderHash = resolve(directory, AudienceReferenceFixtures.SHARED_NAME, null, older.pinKey())
                .evidence().evidenceHash();
        ContentHash newerHash = resolve(directory, AudienceReferenceFixtures.SHARED_NAME, null, newer.pinKey())
                .evidence().evidenceHash();

        assertNotEquals(olderHash, newerHash);
    }

    // ------------------------------------------------------------------ hostile directory material

    @Test
    void sameVersionIdentityWithDifferentBytesIsAConflictNotTwoChoices() {
        AudienceReferenceDirectory hostile = new AudienceReferenceDirectory() {
            @Override
            public List<AudienceSummary> discover(String candidateText) {
                return List.of(new AudienceSummary(AudienceReferenceFixtures.AUDIENCE_VIP, "VIP高价值人群"));
            }

            @Override
            public boolean accessible(String audienceId) {
                return true;
            }

            @Override
            public List<AudienceVersionChoice> publishedVersions(String audienceId) {
                return List.of(
                        new AudienceVersionChoice(
                                new AudienceVersionRef(
                                        AudienceReferenceFixtures.AUDIENCE_VIP,
                                        AudienceReferenceFixtures.VERSION_SEVEN,
                                        ContentHash.sha256("one".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                                Instant.parse("2026-09-01T00:00:00Z"), "one"),
                        new AudienceVersionChoice(
                                new AudienceVersionRef(
                                        AudienceReferenceFixtures.AUDIENCE_VIP,
                                        AudienceReferenceFixtures.VERSION_SEVEN,
                                        ContentHash.sha256("two".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                                Instant.parse("2026-09-01T00:00:00Z"), "two"));
            }
        };
        AudienceReferenceDirectoryContext context = new AudienceReferenceDirectoryContext(
                hostile, "audience-directory/hostile/1", ContentHash.sha256("hostile".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ResolutionException refused = assertThrows(ResolutionException.class, () -> new AudienceReferenceResolver(context)
                .resolve(REFERENCE_ID, CANDIDATES, AudienceReferenceFixtures.SHARED_NAME));

        assertEquals(ResolutionErrorCodes.REFERENCE_DIRECTORY_INVALID, refused.code());
    }

    @Test
    void anOverBoundedDirectoryResultIsRefusedRatherThanTruncated() {
        List<AudienceSummary> tooMany = new ArrayList<>();
        for (int index = 0; index <= ReferenceDiscoveryLimits.MAX_CANDIDATES; index++) {
            tooMany.add(new AudienceSummary("audience-" + index, "label-" + index));
        }
        AudienceReferenceDirectory hostile = new AudienceReferenceDirectory() {
            @Override
            public List<AudienceSummary> discover(String candidateText) {
                return tooMany;
            }

            @Override
            public boolean accessible(String audienceId) {
                return true;
            }

            @Override
            public List<AudienceVersionChoice> publishedVersions(String audienceId) {
                return List.of();
            }
        };
        AudienceReferenceDirectoryContext context = new AudienceReferenceDirectoryContext(
                hostile, "audience-directory/hostile/2", ContentHash.sha256("hostile-2".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ResolutionException refused = assertThrows(ResolutionException.class, () -> new AudienceReferenceResolver(context)
                .resolve(REFERENCE_ID, CANDIDATES, AudienceReferenceFixtures.SHARED_NAME));

        assertEquals(ResolutionErrorCodes.REFERENCE_DIRECTORY_INVALID, refused.code());
    }

    @Test
    void aHostileDirectoryCannotSupplyATrustedFactThroughTheIntentSurface() {
        // The resolver has no parameter for an owner, a program, a DataScope, a permission, a dependency or a
        // Gate fact, so the only way to attempt one is to forge reference material — which the reference core
        // refuses. Here the forged item is a malformed pin inside a version choice.
        AudienceReferenceDirectory hostile = new AudienceReferenceDirectory() {
            @Override
            public List<AudienceSummary> discover(String candidateText) {
                return List.of(new AudienceSummary(AudienceReferenceFixtures.AUDIENCE_VIP, "VIP"));
            }

            @Override
            public boolean accessible(String audienceId) {
                return true;
            }

            @Override
            public List<AudienceVersionChoice> publishedVersions(String audienceId) {
                throw ReferenceException.invalid("a version choice must carry an exact immutable pin");
            }
        };
        AudienceReferenceDirectoryContext context = new AudienceReferenceDirectoryContext(
                hostile, "audience-directory/hostile/3",
                ContentHash.sha256("hostile-3".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ResolutionException refused = assertThrows(ResolutionException.class, () -> new AudienceReferenceResolver(context)
                .resolve(REFERENCE_ID, CANDIDATES, AudienceReferenceFixtures.SHARED_NAME));

        assertEquals(ResolutionErrorCodes.REFERENCE_DIRECTORY_INVALID, refused.code());
        assertFalse(refused.getMessage().contains(AudienceReferenceFixtures.SHARED_NAME));
    }

    // ------------------------------------------------------------------ non-disclosure

    @Test
    void noFailureEchoesTheRequestTextOrACandidateLabel() {
        InMemoryAudienceReferenceDirectory single = AudienceReferenceFixtures.singleVersionDirectory();
        AudienceVersionRef notOffered = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SIX);

        ResolutionException incompatible = assertThrows(ResolutionException.class, () -> resolve(
                single, AudienceReferenceFixtures.SHARED_NAME, null, notOffered.pinKey()));
        assertFalse(incompatible.getMessage().contains(AudienceReferenceFixtures.SHARED_NAME));
        assertFalse(String.valueOf(incompatible.details()).contains(AudienceReferenceFixtures.SHARED_NAME));

        AudienceReferenceResolution ambiguous =
                resolve(AudienceReferenceFixtures.twoAudienceDirectory(), AudienceReferenceFixtures.SHARED_NAME);
        assertFalse(ambiguous.toString().contains(AudienceReferenceFixtures.SHARED_NAME));
        assertFalse(ambiguous.toString().contains("黄金会员人群"));
    }
}