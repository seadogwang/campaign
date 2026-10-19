package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.segment.canonical.CanonicalErrorCodes;
import com.company.campaign.segment.canonical.CanonicalInvariantException;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T07-R2 proof-boundary regression: a real validation result is readable but cannot be manufactured.
 *
 * <p>It has two halves. The reflection/architecture half proves the public surface exposes no constructor and
 * no public (or public static) factory that could create a {@code SegmentValidationResult}, so hash
 * self-consistency alone can never be mistaken for a completed validation run. The in-package adversarial half
 * proves the retained internal construction paths still reject every contradictory state, so even the T07
 * implementation cannot publish a forged success or an issue-less rejection.
 */
class SegmentValidationResultProofBoundaryTest {

    private static final String VERSION = CanonicalizerVersions.CANONICALIZER_VERSION;

    private static void assertRejected(Runnable construction, String expectedCode) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CanonicalInvariantException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    // -------------------------------------------------------- architecture: no public way in

    @Test
    void noPublicConstructorOrFactoryCanCreateAValidationResult() {
        Class<SegmentValidationResult> type = SegmentValidationResult.class;

        assertThat(type.isRecord()).as("the proof boundary is a class, not a record").isFalse();
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type.getPackageName()).isEqualTo("com.company.campaign.segment.validation");

        // getConstructors() returns public constructors only; a public reader must have none.
        assertThat(type.getConstructors()).isEmpty();
        Constructor<?>[] declared = type.getDeclaredConstructors();
        assertThat(declared).as("the proof boundary has at least one declared constructor").isNotEmpty();
        // Every declared constructor stays package-private, whether or not it carries the optional reference
        // graph, so no number of overloads can widen a reader's way in.
        for (Constructor<?> constructor : declared) {
            assertThat(Modifier.isPublic(constructor.getModifiers()))
                    .as("constructor %s must not be public", constructor).isFalse();
            assertThat(Modifier.isProtected(constructor.getModifiers()))
                    .as("constructor %s must not be protected", constructor).isFalse();
        }

        // No public instance method may return the result type (that would be a public factory by name).
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertThat(Modifier.isStatic(method.getModifiers()))
                    .as("public method %s must not be a static factory", method.getName()).isFalse();
            assertThat(method.getReturnType())
                    .as("public method %s must not return a validation result", method.getName())
                    .isNotEqualTo(type);
        }

        // No public static method anywhere on the hierarchy manufactures the type.
        assertThat(Arrays.stream(type.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == type))
                .isEmpty();
    }

    @Test
    void aDownstreamCallerCanReadButNotManufactureAResult() throws Exception {
        SegmentValidationResult genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        // Read side: every documented accessor is public and returns the real artifact of a completed run.
        assertThat(genuine.valid()).isTrue();
        assertThat(genuine.hasBlockingIssues()).isFalse();
        assertThat(genuine.canonicalizerVersion()).isEqualTo(VERSION);
        assertThat(genuine.dslSchemaVersion()).isEqualTo(SegmentValidationFixtures.dslVersion());
        assertThat(genuine.metadataBinding()).isEqualTo(SegmentValidationFixtures.binding());
        assertThat(genuine.editableDslHash()).isNotNull();
        assertThat(genuine.canonicalSegment()).isNotNull();
        assertThat(genuine.canonicalAstHash()).isNotNull();
        assertThat(genuine.issues()).isEmpty();

        // Write side: the public API offers only inspection; the sole constructor is not public.
        assertThat(SegmentValidationResult.class.getConstructors()).isEmpty();
        assertThatThrownBy(() -> SegmentValidationResult.class.getConstructor(
                        String.class, SchemaVersion.class,
                        com.company.campaign.segment.dsl.contract.MetadataBinding.class,
                        ContentHash.class,
                        com.company.campaign.segment.canonical.CanonicalSegment.class,
                        ContentHash.class, List.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void accessorNamesAndValueSemanticsMatchTheFormerRecord() {
        SegmentValidationResult first = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        SegmentValidationResult second = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).startsWith("SegmentValidationResult[")
                .contains("canonicalizerVersion=", "issues=");
        assertThat(List.of(first.issues())).isEqualTo(List.of(second.issues()));
    }

    // ---------------------------------------------- in-package: internal paths reject contradictions

    @Test
    void internalSuccessFactoryRejectsAHashThatIsNotTheCanonicalRootHash() {
        SegmentValidationResult genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        ContentHash forged = ContentHash.sha256("forged".getBytes(StandardCharsets.UTF_8));

        assertRejected(() -> SegmentValidationResult.success(
                        VERSION, genuine.dslSchemaVersion(), genuine.metadataBinding(), genuine.editableDslHash(),
                        genuine.canonicalSegment(), forged),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }

    @Test
    void internalSuccessFactoryRejectsRootPinsThatDisagree() {
        SegmentValidationResult genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        assertRejected(() -> SegmentValidationResult.success(
                        VERSION, new SchemaVersion(9, 9), genuine.metadataBinding(), genuine.editableDslHash(),
                        genuine.canonicalSegment(), genuine.canonicalAstHash()),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
        assertRejected(() -> SegmentValidationResult.success(
                        VERSION, genuine.dslSchemaVersion(), SegmentValidationFixtures.binding("metadata-99"),
                        genuine.editableDslHash(), genuine.canonicalSegment(), genuine.canonicalAstHash()),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }

    @Test
    void internalConstructionRejectsASuccessThatCarriesABlockingIssue() {
        SegmentValidationResult genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        assertRejected(() -> new SegmentValidationResult(
                        VERSION, genuine.dslSchemaVersion(), genuine.metadataBinding(), genuine.editableDslHash(),
                        genuine.canonicalSegment(), genuine.canonicalAstHash(),
                        List.of(Issue.error("X", "forged blocking issue"))),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }

    @Test
    void internalRejectionFactoryRejectsAnIssueLessOrWarningOnlyRejection() {
        assertRejected(() -> SegmentValidationResult.rejected(VERSION, null, null, null, List.of()),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
        assertRejected(() -> SegmentValidationResult.rejected(VERSION, null, null, null,
                        List.of(new Issue("W", IssueSeverity.WARNING, null, null, null, "warning only", Map.of()))),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }

    @Test
    void internalConstructionRejectsAHashWithoutASegmentAndAnArbitraryVersion() {
        SegmentValidationResult genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        assertRejected(() -> new SegmentValidationResult(
                        VERSION, null, null, null, null,
                        ContentHash.sha256("x".getBytes(StandardCharsets.UTF_8)), List.of()),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
        assertRejected(() -> SegmentValidationResult.success(
                        "segment-canonicalizer/9.9", genuine.dslSchemaVersion(), genuine.metadataBinding(),
                        genuine.editableDslHash(), genuine.canonicalSegment(), genuine.canonicalAstHash()),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
        assertRejected(() -> new SegmentValidationResult(
                        VERSION, null, null, null, null, null,
                        Arrays.asList(Issue.error("X", "blocking"), null)),
                CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }

    @Test
    void aGenuineResultFromTheValidatorIsStillValid() {
        assertThat(SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument()).valid()).isTrue();
        assertThat(SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), SegmentValidationFixtures.allowAll(), null).valid()).isFalse();
    }
}