package com.company.campaign.evaluation.segment.loader;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves an asset reference to its <em>real</em> filesystem path and requires that path to stay beneath the
 * real evaluation root.
 *
 * <p>{@link EvaluationRoot} validates references lexically: it is a pure function from the configured root and
 * the reference text, and it is deliberately blind to what a symlink or NTFS junction points at. That is the
 * right property for a deterministic corpus contract — the same reference has the same verdict on every
 * machine — but it is not sufficient on its own. A reference such as
 * {@code evaluation/segment/cases/linked/case.json} is lexically inside the root while
 * {@code linked} can be a junction to {@code D:\somewhere\else}, so a manifest could name a file outside the
 * evaluation root and still pass every lexical check.
 *
 * <p>This resolver closes that gap at the only moment it can be closed correctly: immediately before the bytes
 * are read. It resolves both the evaluation root and the referenced file with {@link Path#toRealPath}, which
 * follows symbolic links and Windows junctions, then requires the file's real path to start with the root's
 * real path. The loader reads from the already-resolved real path, so a link cannot be retargeted between the
 * check and the read to traverse a second time.
 *
 * <p>Policy for links that stay inside the evaluation root: they are <strong>accepted</strong>. Containment is
 * decided by where the bytes actually live, not by whether a link was used to reach them, so an internal link
 * to a file that is genuinely inside the root is safe and is not rejected merely for being a link.
 *
 * <p>Failures reuse the loader's existing stable codes so callers branch exactly as before: a missing or
 * dangling target is {@link EvaluationErrorCodes#ASSET_NOT_FOUND}, an unresolvable or unreadable target is
 * {@link EvaluationErrorCodes#ASSET_UNREADABLE} and a real path outside the root is
 * {@link EvaluationErrorCodes#PATH_OUTSIDE_ROOT}. No failure carries the resolved real path, because that path
 * may name a location outside the evaluation root and must not leak into a report.
 *
 * <p>It is public so a later task that must read an evaluation asset through the same accepted boundary — the
 * T09A deterministic fixture executor — reuses this one implementation instead of re-deriving containment.
 */
public final class RealPathResolver {

    private final Path evaluationRoot;

    /** Creates the resolver for one explicitly supplied evaluation root. */
    public RealPathResolver(Path evaluationRoot) {
        this.evaluationRoot = Objects.requireNonNull(evaluationRoot, "evaluationRoot");
    }

    /**
     * Resolves an existing asset to its real path, requires it to be a regular file beneath the real evaluation
     * root and returns that real path for reading.
     *
     * <p>{@code candidate} is the lexically validated path produced by {@link EvaluationRoot#resolve(String)};
     * {@code reference} is the original repository-relative text and is used only for stable diagnostics.
     */
    public Path resolveExistingFile(Path candidate, String reference) {
        Path realEvaluationRoot = toRealPath(evaluationRoot, reference);
        Path realCandidate = toRealPath(candidate, reference);
        requireContained(realEvaluationRoot, realCandidate, reference);
        if (!Files.isRegularFile(realCandidate)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_NOT_FOUND,
                    "Asset '" + reference + "' does not exist or is not a regular file",
                    Map.of("reference", reference));
        }
        return realCandidate;
    }

    /**
     * The containment decision on its own, kept separate from filesystem resolution so the security rule can be
     * exercised directly without depending on the platform's ability to create links.
     *
     * <p>Both arguments must already be real paths. Comparison is component-wise through
     * {@link Path#startsWith(Path)}, so a sibling directory whose name merely begins with the root's name
     * (for example {@code evaluation/segment-escape}) is not accepted as contained.
     */
    static Path requireContained(Path realEvaluationRoot, Path realCandidate, String reference) {
        if (!realCandidate.startsWith(realEvaluationRoot)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "Asset '" + reference + "' resolves outside the evaluation root",
                    Map.of("reference", reference));
        }
        return realCandidate;
    }

    private static Path toRealPath(Path path, String reference) {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_NOT_FOUND,
                    "Asset '" + reference + "' does not exist or is not a regular file",
                    Map.of("reference", reference),
                    exception);
        } catch (IOException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_UNREADABLE,
                    "Asset '" + reference + "' cannot be resolved to a real path",
                    Map.of("reference", reference),
                    exception);
        }
    }
}