package com.company.campaign.evaluation.segment.loader;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The configured evaluation root and the rules for resolving an asset reference against it.
 *
 * <p>Every reference in an evaluation asset is a repository-relative path, and every one of them is
 * resolved through here. The rules are deliberately hostile, because these paths come from files that a
 * corpus author — and, later, corpus tooling — writes:
 *
 * <ul>
 *   <li>absolute references are refused, including drive-qualified, UNC and root-relative forms, so a
 *       reference can never escape the repository by looking like a full path;</li>
 *   <li>parent-directory segments are refused before normalization, so {@code ..} is reported as the
 *       problem it is rather than being silently folded away;</li>
 *   <li>the resolved path must start with the evaluation root, so a syntactically relative path outside
 *       {@code evaluation/segment} is still rejected;</li>
 *   <li>backslashes are refused, so one reference has exactly one spelling on every platform.</li>
 * </ul>
 *
 * <p>The check is lexical. It decides containment from the configured root and the reference text, not from
 * where a symlink happens to point, which keeps the outcome identical across machines and test
 * directories.
 *
 * <p>That determinism is exactly why this class is not sufficient by itself. Lexical containment says a
 * reference <em>looks</em> like it stays under {@code evaluation/segment}; it cannot say that the bytes a
 * reference reaches <em>are</em> under it, because a symlink or NTFS junction beneath the root can point
 * anywhere. Real-path containment is therefore mandatory at I/O time and lives in {@link RealPathResolver},
 * which the loader invokes immediately before reading bytes. The two layers are complementary and both are
 * required: this class gives a stable, machine-independent rejection of hostile reference <em>text</em>; the
 * resolver gives the platform-honest guarantee about the <em>target</em> the text actually reaches.
 */
public final class EvaluationRoot {

    /** Default location of the evaluation assets inside the repository. */
    public static final String DEFAULT_EVALUATION_ROOT = "evaluation/segment";

    private final Path repositoryRoot;
    private final Path evaluationRoot;

    private EvaluationRoot(Path repositoryRoot, Path evaluationRoot) {
        this.repositoryRoot = repositoryRoot;
        this.evaluationRoot = evaluationRoot;
    }

    /** Uses {@link #DEFAULT_EVALUATION_ROOT} as the evaluation root. */
    public static EvaluationRoot of(Path repositoryRoot) {
        return of(repositoryRoot, DEFAULT_EVALUATION_ROOT);
    }

    /** Uses an explicit repository-relative evaluation root. */
    public static EvaluationRoot of(Path repositoryRoot, String evaluationRootReference) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Path normalizedRepository = repositoryRoot.toAbsolutePath().normalize();
        Path relativeRoot = validateReference(evaluationRootReference);
        Path normalizedEvaluationRoot = normalizedRepository.resolve(relativeRoot).normalize();
        if (!normalizedEvaluationRoot.startsWith(normalizedRepository)
                || normalizedEvaluationRoot.equals(normalizedRepository)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "Evaluation root '" + evaluationRootReference + "' is not beneath the repository root",
                    Map.of("evaluationRoot", String.valueOf(evaluationRootReference)));
        }
        return new EvaluationRoot(normalizedRepository, normalizedEvaluationRoot);
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public Path evaluationRoot() {
        return evaluationRoot;
    }

    /**
     * Resolves a repository-relative asset reference to an absolute path beneath the evaluation root.
     *
     * <p>A path is returned even when the file does not exist; existence and hashing are the loader's
     * concern because only it knows what the reference was supposed to contain.
     */
    public Path resolve(String reference) {
        Path resolved = repositoryRoot.resolve(validateReference(reference)).normalize();
        if (!resolved.startsWith(evaluationRoot)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "Asset reference '" + reference + "' resolves outside " + referenceFor(evaluationRoot),
                    Map.of("reference", String.valueOf(reference),
                            "evaluationRoot", referenceFor(evaluationRoot)));
        }
        return resolved;
    }

    /** The repository-relative, forward-slash spelling of a file inside the repository. */
    public String referenceFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repositoryRoot)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "File " + normalized + " is not beneath repository root " + repositoryRoot);
        }
        return repositoryRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private static Path validateReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED, "Asset reference must not be empty");
        }
        if (reference.indexOf('\\') >= 0) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Asset reference '" + reference + "' must use forward slashes",
                    Map.of("reference", reference));
        }
        Path raw;
        try {
            raw = Path.of(reference);
        } catch (InvalidPathException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_MALFORMED,
                    "Asset reference '" + reference + "' is not a well-formed path",
                    Map.of("reference", reference), exception);
        }
        if (raw.getRoot() != null || raw.isAbsolute()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_ABSOLUTE,
                    "Asset reference '" + reference + "' must not be absolute",
                    Map.of("reference", reference));
        }
        for (Path segment : raw) {
            String text = segment.toString();
            if (text.isEmpty()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.PATH_MALFORMED,
                        "Asset reference '" + reference + "' contains an empty path segment",
                        Map.of("reference", reference));
            }
            if ("..".equals(text)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.PATH_TRAVERSAL,
                        "Asset reference '" + reference + "' contains a parent-directory segment",
                        Map.of("reference", reference));
            }
            if (".".equals(text)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.PATH_MALFORMED,
                        "Asset reference '" + reference + "' must be normalized and must not contain '.'",
                        Map.of("reference", reference));
            }
        }
        return raw;
    }
}