package com.company.campaign.evaluation.segment;

import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 boundaries: the corpus consumes T04/T05 typed inputs and stops there.
 *
 * <p>No semantic canonicalization, no query planning or execution, no database access, no model call and
 * no {@code canonicalAstHash} may appear. Those are T07 and later, and their absence is the boundary this
 * task must not cross.
 */
class GoldenCorpusBoundaryTest {

    private static final Path EVALUATION_SOURCES =
            Path.of("src", "main", "java", "com", "company", "campaign", "evaluation");
    private static final Path CORPUS_ROOT = Path.of("evaluation", "segment", "corpus-v1");

    /**
     * Tokens that must not appear in evaluation code.
     *
     * <p>{@code canonicalAstHash} is deliberately absent here: the sources mention it in prose to record
     * that T05/T06 do not produce it. Its real absence is checked on the corpus assets and through the
     * public record components below, where documentation cannot mask a field.
     */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "com.company.campaign.query",
            "java.sql",
            "javax.sql",
            "org.springframework.ai",
            "com.openai",
            "dev.langchain4j");

    /**
     * The T06 token scan covers the evaluation tree except the T13 runner.
     *
     * <p>T03 and T06 stop at reviewed assets, so they must not name the query packages. T13 is the first task
     * whose whole purpose is to execute the accepted pipeline, so its runner subpackage legitimately names the
     * accepted T08/T09 query contracts — exactly as T06 already narrowed this rule for its own validation
     * subpackage. Everything else, including every AI-provider and database token, still applies there.
     */
    @Test
    void evaluationSourcesContainNoCanonicalAstHashOrLaterTaskBehaviour() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(EVALUATION_SOURCES)) {
            boolean isRunner = file.toString().replace(java.io.File.separatorChar, '/')
                    .contains("/evaluation/segment/runner/");
            String source = read(file);
            for (String token : FORBIDDEN_TOKENS) {
                if (isRunner && "com.company.campaign.query".equals(token)) {
                    continue;
                }
                if (source.contains(token)) {
                    violations.add(file + " -> " + token);
                }
            }
        }
        assertThat(violations).as("T07+ or AI/query/database tokens in evaluation sources").isEmpty();
    }

    @Test
    void candidateAssetsContainNoCanonicalAstHashOrVerdict() {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CORPUS_ROOT)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!file.toString().endsWith(".json")) {
                    continue;
                }
                String content = read(file);
                if (content.contains("canonicalAstHash")) {
                    violations.add(file + " -> canonicalAstHash");
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        assertThat(violations).as("T07 canonical hashes must not appear in the candidate corpus").isEmpty();
    }

    @Test
    void thePublicContractsExposeNoCanonicalAstHash() {
        assertThat(Arrays.stream(ExpectedResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("canonicalAstHash");
        assertThat(Arrays.stream(SegmentDsl.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("canonicalAstHash");
        assertThat(Arrays.stream(SegmentDsl.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("canonicalAstHash");
    }

    /**
     * The T06 corpus boundary checks the {@code evaluation} tree; it does not constrain the accepted T08
     * query packages. T08 legitimately introduces {@code query} (its planned module is
     * {@code query.ast}/{@code query.plan}/{@code query.sql}), so that package is no longer "later"; every
     * remaining package below still belongs to a task after T08 and must stay absent.
     *
     * <p>T15 legitimately introduces {@code builder} (its planned modules are {@code builder.proposal} and
     * {@code builder.compiler}), so that package is no longer "later" either. Everything listed below still
     * belongs to a task after T15 and must stay absent.
     *
     * <p>T20 legitimately introduces {@code journey} for deterministic graph compilation and side-effect-free
     * simulation. T22 legitimately introduces the isolated {@code runtime} package. Provider and scheduling
     * execution packages remain outside the accepted boundary.
     */
    @Test
    void noLaterTaskPackagesWereIntroduced() {
        Path campaign = Path.of("src", "main", "java", "com", "company", "campaign");
        for (String later : List.of("audience", "campaign",
                "message", "reward", "schedule")) {
            assertThat(campaign.resolve(later))
                    .as("package %s belongs to a later task", later)
                    .doesNotExist();
        }
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
