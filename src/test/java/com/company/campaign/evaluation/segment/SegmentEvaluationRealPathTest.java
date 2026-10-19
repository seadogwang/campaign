package com.company.campaign.evaluation.segment;

import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.testsupport.EvaluationCorpusFixture;
import com.company.campaign.evaluation.testsupport.PathLinkSupport;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-path containment through the loader: a reference that is lexically inside the evaluation root but
 * resolves, through a symlink or junction, to a file outside it must be rejected before its bytes are read.
 *
 * <p>Each escaping corpus is built so that the declared SHA-256 hash still matches the bytes on the far side
 * of the link. Hash verification and canonical-byte verification therefore cannot be what rejects it: the
 * only remaining defence is real-path containment.
 *
 * <p>Windows accounts without symlink privilege cannot create file symbolic links, so those cases fall back to
 * an NTFS directory junction, which {@link Path#toRealPath} follows identically. The containment decision is
 * additionally covered without any link in {@code RealPathResolverTest}.
 */
class SegmentEvaluationRealPathTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void aNormalAssetUnderTheRealEvaluationRootStillLoads() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        CorpusBundle bundle = loader().loadCorpus();

        assertThat(bundle.cases()).extracting("caseId").containsExactly("case-attr-001");
        assertThat(loader().readVerifiedBytes(
                EvaluationCorpusFixture.reference("cases/case-attr-001.json"), null)).isNotEmpty();
    }

    @Test
    void aFileLinkInsideTheEvaluationRootThatPointsOutsideIsRejected() throws IOException {
        boolean fileLinksAvailable = fileLinksAreAvailable();
        String declaredCasePath = fileLinksAvailable
                ? EvaluationCorpusFixture.reference("cases/case-attr-001.json")
                : EvaluationCorpusFixture.reference("cases/escape/case-attr-001.json");

        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .declaredCasePath(declaredCasePath))
                .write();

        Path writtenCase = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/case-attr-001.json"));
        byte[] caseBytes = Files.readAllBytes(writtenCase);

        // The very same bytes live outside the evaluation root, so the manifest hash still matches them.
        Path outsideFile = repositoryRoot.resolve("outside-store/case-attr-001.json");
        Files.createDirectories(outsideFile.getParent());
        Files.write(outsideFile, caseBytes);
        Files.delete(writtenCase);

        if (fileLinksAvailable) {
            assertThat(PathLinkSupport.tryCreateFileLink(writtenCase, outsideFile)).isTrue();
        } else {
            Path escapeDirectory = repositoryRoot.resolve(
                    EvaluationCorpusFixture.reference("cases/escape"));
            Assumptions.assumeTrue(
                    PathLinkSupport.tryCreateDirectoryLink(escapeDirectory, outsideFile.getParent()),
                    "this platform cannot create a filesystem link");
        }

        // Precondition: the reference passes the lexical containment check, so only real-path resolution can
        // reject it. Without the resolver this corpus would load.
        assertThat(loader().root().resolve(declaredCasePath)).isNotNull();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @Test
    void aLinkedDirectoryInsideTheEvaluationRootThatPointsOutsideIsRejected() throws IOException {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .declaredCasePath(EvaluationCorpusFixture.reference("cases/linked/case-attr-001.json")))
                .write();

        Path writtenCase = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/case-attr-001.json"));
        Path outsideDirectory = repositoryRoot.resolve("outside-store");
        Files.createDirectories(outsideDirectory);
        Files.move(writtenCase, outsideDirectory.resolve("case-attr-001.json"));

        Path linkedDirectory = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/linked"));
        Assumptions.assumeTrue(
                PathLinkSupport.tryCreateDirectoryLink(linkedDirectory, outsideDirectory),
                "this platform cannot create a directory link");

        // Precondition: the reference passes the lexical containment check, so only real-path resolution can
        // reject it. Without the resolver this corpus would load.
        String linkedReference = EvaluationCorpusFixture.reference("cases/linked/case-attr-001.json");
        assertThat(loader().root().resolve(linkedReference)).isNotNull();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @Test
    void aLinkThatStaysInsideTheSameRealEvaluationRootIsAccepted() throws IOException {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .declaredCasePath(EvaluationCorpusFixture.reference("cases/linked/case-attr-001.json")))
                .write();

        Path writtenCase = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/case-attr-001.json"));
        Path internalStore = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/real-store"));
        Files.createDirectories(internalStore);
        Files.move(writtenCase, internalStore.resolve("case-attr-001.json"));

        Path link = repositoryRoot.resolve(EvaluationCorpusFixture.reference("cases/linked"));
        Assumptions.assumeTrue(PathLinkSupport.tryCreateDirectoryLink(link, internalStore),
                "this platform cannot create a directory link");

        // Policy: containment is decided by where the bytes really live, not by whether a link was used.
        // An internal link to a file genuinely inside the root is therefore accepted.
        CorpusBundle bundle = loader().loadCorpus();

        assertThat(bundle.cases()).extracting("caseId").containsExactly("case-attr-001");
    }

    @Test
    void hashAndCanonicalByteChecksStillRunForALinkedRealPath() throws IOException {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .declaredCasePath(EvaluationCorpusFixture.reference("cases/linked/case-attr-001.json")))
                .write();

        Path writtenCase = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/case-attr-001.json"));
        Path internalStore = repositoryRoot.resolve(
                EvaluationCorpusFixture.reference("cases/real-store"));
        Files.createDirectories(internalStore);
        Path realCase = internalStore.resolve("case-attr-001.json");
        Files.copy(writtenCase, realCase);

        Path link = repositoryRoot.resolve(EvaluationCorpusFixture.reference("cases/linked"));
        Assumptions.assumeTrue(PathLinkSupport.tryCreateDirectoryLink(link, internalStore),
                "this platform cannot create a directory link");

        // A canonical asset reached through an internal link still parses, and the declared hash still binds
        // to the bytes read from the resolved real path.
        CorpusBundle bundle = loader().loadCorpus();
        assertThat(bundle.cases()).extracting("caseId").containsExactly("case-attr-001");

        // The canonical-byte check still runs on the real path: the same file, one leading space, is valid
        // JSON but is no longer the canonical serialization of the value it contains.
        String canonical = Files.readString(realCase, StandardCharsets.UTF_8);
        Files.writeString(realCase, " " + canonical, StandardCharsets.UTF_8);

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(
                        EvaluationCorpusFixture.reference("cases/linked/case-attr-001.json")),
                EvaluationErrorCodes.ASSET_NOT_CANONICAL);
    }

    private SegmentEvaluationLoader loader() {
        return new SegmentEvaluationLoader(repositoryRoot);
    }

    /** Probes whether this account can create a file symbolic link in the temporary repository. */
    private boolean fileLinksAreAvailable() {
        Path probe = repositoryRoot.resolve("link-probe");
        try {
            Files.createDirectories(probe);
            Path target = probe.resolve("target.txt");
            Files.writeString(target, "probe", StandardCharsets.UTF_8);
            return PathLinkSupport.tryCreateFileLink(probe.resolve("link.txt"), target);
        } catch (IOException exception) {
            return false;
        }
    }
}