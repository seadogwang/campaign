package com.company.campaign.evaluation.segment.loader;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.testsupport.PathLinkSupport;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-path containment rule, exercised directly.
 *
 * <p>The decision itself is tested without any filesystem link, so the security assertion runs everywhere,
 * including on Windows accounts that cannot create symbolic links. The link-based cases then prove that the
 * rule is what the resolver actually applies to a redirected path.
 */
class RealPathResolverTest {

    @TempDir
    Path base;

    @Test
    void aRealPathBeneathTheRealRootIsContained() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path candidate = Files.createDirectories(realRoot.resolve("cases")).resolve("case.json");
        Files.writeString(candidate, "{}", StandardCharsets.UTF_8);

        assertThat(RealPathResolver.requireContained(realRoot, candidate, "evaluation/segment/cases/case.json"))
                .isEqualTo(candidate);
    }

    @Test
    void aRealPathOutsideTheRealRootIsRejected() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Path candidate = outside.resolve("secret.json");
        Files.writeString(candidate, "{}", StandardCharsets.UTF_8);

        ContractAssertions.assertFailsWithCode(
                () -> RealPathResolver.requireContained(
                        realRoot, candidate, "evaluation/segment/cases/secret.json"),
                EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @Test
    void aSiblingWhoseNameSharesTheRootPrefixIsNotContained() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path sibling = Files.createDirectories(base.resolve("evaluation/segment-escape"));
        Path candidate = sibling.resolve("secret.json");
        Files.writeString(candidate, "{}", StandardCharsets.UTF_8);

        // Containment is component-wise. A sibling that merely starts with the same characters is outside.
        ContractAssertions.assertFailsWithCode(
                () -> RealPathResolver.requireContained(
                        realRoot, candidate, "evaluation/segment/cases/secret.json"),
                EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @Test
    void aMissingCandidateMapsToAssetNotFoundWithoutLeakingTheTarget() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path candidate = realRoot.resolve("cases/absent.json");

        ContractAssertions.assertFailsWithCode(
                () -> new RealPathResolver(realRoot)
                        .resolveExistingFile(candidate, "evaluation/segment/cases/absent.json"),
                EvaluationErrorCodes.ASSET_NOT_FOUND);
    }

    @Test
    void aDirectoryTargetIsNotARegularFile() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path directory = Files.createDirectories(realRoot.resolve("cases/dir.json"));

        ContractAssertions.assertFailsWithCode(
                () -> new RealPathResolver(realRoot)
                        .resolveExistingFile(directory, "evaluation/segment/cases/dir.json"),
                EvaluationErrorCodes.ASSET_NOT_FOUND);
    }

    @Test
    void aRegularFileBeneathTheRootResolvesToItsRealPath() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path file = Files.createDirectories(realRoot.resolve("cases")).resolve("case.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        Path resolved = new RealPathResolver(realRoot)
                .resolveExistingFile(file, "evaluation/segment/cases/case.json");

        assertThat(resolved).isEqualTo(file.toRealPath());
        assertThat(resolved.startsWith(realRoot.toRealPath())).isTrue();
    }

    @Test
    void aDirectoryLinkThatEscapesTheRootIsRejected() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path outside = Files.createDirectories(base.resolve("outside-store"));
        Files.writeString(outside.resolve("case.json"), "{}", StandardCharsets.UTF_8);

        Files.createDirectories(realRoot.resolve("cases"));
        Path link = realRoot.resolve("cases/linked");
        Assumptions.assumeTrue(PathLinkSupport.tryCreateDirectoryLink(link, outside),
                "this platform cannot create a directory link");

        ContractAssertions.assertFailsWithCode(
                () -> new RealPathResolver(realRoot)
                        .resolveExistingFile(link.resolve("case.json"),
                                "evaluation/segment/cases/linked/case.json"),
                EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @Test
    void aDirectoryLinkThatStaysInsideTheRootIsAccepted() throws IOException {
        Path realRoot = Files.createDirectories(base.resolve("evaluation/segment"));
        Path store = Files.createDirectories(realRoot.resolve("cases/real-store"));
        Files.writeString(store.resolve("case.json"), "{}", StandardCharsets.UTF_8);

        Path link = realRoot.resolve("cases/linked");
        Assumptions.assumeTrue(PathLinkSupport.tryCreateDirectoryLink(link, store),
                "this platform cannot create a directory link");

        Path resolved = new RealPathResolver(realRoot)
                .resolveExistingFile(link.resolve("case.json"), "evaluation/segment/cases/linked/case.json");

        assertThat(resolved).isEqualTo(store.resolve("case.json").toRealPath());
    }
}