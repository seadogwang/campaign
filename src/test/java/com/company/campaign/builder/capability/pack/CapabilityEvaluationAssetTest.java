package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A pack evaluation asset pins its identity, version and content hash and keeps positive and negative
 * fixtures strictly apart: a fixture may only sit in the list its own expectation belongs to, the two lists
 * are each non-empty, and a fixture id is unique across both. An asset that cannot tell a valid payload from
 * an invalid one is refused.
 */
class CapabilityEvaluationAssetTest {

    @Test
    void theAssetSeparatesPositiveFromNegativeFixtures() {
        CapabilityEvaluationAsset asset = new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-message-valid", Expectation.ACCEPTED)),
                List.of(fixture("fixture-message-unknown-field", Expectation.REJECTED)));

        assertThat(asset.assetId()).isEqualTo("t19-evaluation-v1");
        assertThat(asset.assetVersion()).isEqualTo("1.0");
        assertThat(asset.contentHash()).isEqualTo(hash("asset"));
        assertThat(asset.positiveFixtures()).extracting(CapabilityEvaluationFixture::fixtureId)
                .containsExactly("fixture-message-valid");
        assertThat(asset.negativeFixtures()).extracting(CapabilityEvaluationFixture::fixtureId)
                .containsExactly("fixture-message-unknown-field");
        assertThat(asset.positiveFixtures().get(0).isPositive()).isTrue();
        assertThat(asset.negativeFixtures().get(0).isPositive()).isFalse();
        assertThat(asset.allFixtures()).hasSize(2);
    }

    @Test
    void aFixtureOfTheWrongPolarityFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-message-valid", Expectation.REJECTED)),
                List.of(fixture("fixture-message-invalid", Expectation.REJECTED))),
                "capabilityPackEvaluationFixturePolarity");
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-message-valid", Expectation.ACCEPTED)),
                List.of(fixture("fixture-message-invalid", Expectation.ACCEPTED))),
                "capabilityPackEvaluationFixturePolarity");
    }

    @Test
    void anAssetWithoutBothPolaritiesFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(), List.of(fixture("fixture-message-invalid", Expectation.REJECTED))),
                "capabilityPackEvaluationFixturePolarity");
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-message-valid", Expectation.ACCEPTED)), List.of()),
                "capabilityPackEvaluationFixturePolarity");
    }

    @Test
    void aFixtureIdRepeatedAcrossThePolaritiesFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-shared", Expectation.ACCEPTED)),
                List.of(fixture("fixture-shared", Expectation.REJECTED))),
                "capabilityPackEvaluationFixtureDuplicate");
    }

    @Test
    void anUnboundedFixtureListFailsClosedAtExactlyTheBound() {
        assertThat(new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                fixtures(CapabilityEvaluationAsset.MAX_FIXTURES, "positive", Expectation.ACCEPTED),
                List.of(fixture("fixture-negative", Expectation.REJECTED))).positiveFixtures())
                .hasSize(CapabilityEvaluationAsset.MAX_FIXTURES);

        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                fixtures(CapabilityEvaluationAsset.MAX_FIXTURES + 1, "positive", Expectation.ACCEPTED),
                List.of(fixture("fixture-negative", Expectation.REJECTED))),
                "capabilityPackEvaluationPositiveFixtures");
    }

    @Test
    void aMissingAssetPinFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationAsset(null, "1.0", hash("asset"), positives(), negatives()),
                "capabilityPackEvaluationAssetId");
        assertInvalid(() -> new CapabilityEvaluationAsset("t19 evaluation", "1.0", hash("asset"), positives(),
                negatives()), "capabilityPackEvaluationAssetId");
        for (String version : List.of("", " ", "1.0 ", "-1")) {
            assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", version, hash("asset"),
                    positives(), negatives()), "capabilityPackEvaluationAssetVersion");
        }
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", null, hash("asset"), positives(),
                negatives()), "capabilityPackEvaluationAssetVersion");
        assertInvalid(() -> new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", null, positives(),
                negatives()), "capabilityPackEvaluationAssetHash");
    }

    @Test
    void anAssetIdCarryingTrustedAuthorityContentFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationAsset("tenant-evaluation", "1.0", hash("asset"),
                positives(), negatives()), "capabilityPackEvaluationAssetId");
    }

    @Test
    void aMalformedOrForbiddenFixtureIdFailsClosedWithoutEchoingIt() {
        assertInvalidWithoutDisclosure(() -> fixture("fixture message", Expectation.ACCEPTED),
                "capabilityPackEvaluationFixtureId", "fixture message");
        assertInvalid(() -> new CapabilityEvaluationFixture(null, Expectation.ACCEPTED, hash("payload")),
                "capabilityPackEvaluationFixtureId");
        assertInvalid(() -> fixture("fixture-tenant-id", Expectation.ACCEPTED),
                "capabilityPackEvaluationFixtureId");
    }

    @Test
    void aFixtureMissingItsExpectationOrHashFailsClosed() {
        assertInvalid(() -> new CapabilityEvaluationFixture("fixture-message-valid", null, hash("payload")),
                "capabilityPackEvaluationFixtureExpectation");
        assertInvalid(() -> new CapabilityEvaluationFixture("fixture-message-valid", Expectation.ACCEPTED, null),
                "capabilityPackEvaluationFixtureHash");
    }

    @Test
    void eachPolarityIsCanonicalizedToOneDeterministicOrder() {
        CapabilityEvaluationAsset asset = new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-message-valid", Expectation.ACCEPTED),
                        fixture("fixture-flow-start-valid", Expectation.ACCEPTED)),
                List.of(fixture("fixture-message-unknown", Expectation.REJECTED)));

        assertThat(asset.positiveFixtures()).extracting(CapabilityEvaluationFixture::fixtureId)
                .containsExactly("fixture-flow-start-valid", "fixture-message-valid");
        assertThat(asset).isEqualTo(new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                List.of(fixture("fixture-flow-start-valid", Expectation.ACCEPTED),
                        fixture("fixture-message-valid", Expectation.ACCEPTED)),
                List.of(fixture("fixture-message-unknown", Expectation.REJECTED))))
                .hasSameHashCodeAs(asset);
    }

    @Test
    void theAssetFreezesEveryFixtureListItCarries() {
        List<CapabilityEvaluationFixture> positive =
                new ArrayList<>(List.of(fixture("fixture-message-valid", Expectation.ACCEPTED)));
        CapabilityEvaluationAsset asset = new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                positive, List.of(fixture("fixture-message-unknown", Expectation.REJECTED)));

        positive.clear();

        assertThat(asset.positiveFixtures()).hasSize(1);
        assertThatThrownBy(() -> asset.positiveFixtures().add(fixture("fixture-other", Expectation.ACCEPTED)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asset.allFixtures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theDiagnosticsAreRedacted() {
        CapabilityEvaluationAsset asset = new CapabilityEvaluationAsset("t19-evaluation-v1", "1.0", hash("asset"),
                positives(), negatives());

        assertThat(asset.toString()).contains("t19-evaluation-v1", "positive=1", "negative=1")
                .doesNotContain("fixture-message-valid", "fixture-message-unknown");
    }

    private static List<CapabilityEvaluationFixture> positives() {
        return List.of(fixture("fixture-message-valid", Expectation.ACCEPTED));
    }

    private static List<CapabilityEvaluationFixture> negatives() {
        return List.of(fixture("fixture-message-unknown", Expectation.REJECTED));
    }

    private static List<CapabilityEvaluationFixture> fixtures(int size, String prefix, Expectation expectation) {
        List<CapabilityEvaluationFixture> fixtures = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            fixtures.add(fixture("fixture-" + prefix + "-" + index, expectation));
        }
        return fixtures;
    }

    private static CapabilityEvaluationFixture fixture(String id, Expectation expectation) {
        return new CapabilityEvaluationFixture(id, expectation, hash(id));
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                });
    }

    private static void assertInvalidWithoutDisclosure(Runnable construction, String invariant, String rejected) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    if (rejected == null || rejected.isBlank() || rejected.length() < 4) {
                        return;
                    }
                    assertThat(failure.getMessage()).doesNotContain(rejected);
                });
    }
}