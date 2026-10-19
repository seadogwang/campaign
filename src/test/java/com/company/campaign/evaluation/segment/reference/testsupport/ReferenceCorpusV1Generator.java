package com.company.campaign.evaluation.segment.reference.testsupport;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusVersions;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The deterministic offline generator for the committed T18 Existing Audience Reference corpus.
 *
 * <p>It reads no clock, network, random source or default locale: every asset is a pure function of the
 * reviewed {@code ReferenceCorpusBlueprints}, and the same blueprints always produce byte-identical output.
 * The generator owns the only supported way to write the corpus, and it refuses to overwrite a review ledger
 * that carries a real non-pending decision, because that would silently discard human review.
 *
 * <p>It is deliberately never invoked by an ordinary test. {@link #differences} is the read-only check a
 * focused test runs, and {@link #write} happens only when the
 * {@link #REGENERATE_PROPERTY regeneration property} is explicitly set by a host.
 */
public final class ReferenceCorpusV1Generator {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** System property that turns the consistency check into an explicit regeneration. */
    public static final String REGENERATE_PROPERTY = "campaign.reference-corpus.regenerate";

    private ReferenceCorpusV1Generator() {
    }

    /** Every committed asset, keyed by repository-relative path. */
    public static Map<String, byte[]> generate() {
        return ReferenceCorpusV1Assets.build();
    }

    /** The committed reference-corpus root, relative to the repository root. */
    public static Path corpusRoot(Path repositoryRoot) {
        return repositoryRoot.resolve(ReferenceCorpusVersions.CORPUS_ROOT);
    }

    /**
     * Writes the generated assets beneath the repository root.
     *
     * <p>Refuses when a committed review ledger already records a real decision: a regeneration may add new
     * pending ledgers, but it must never erase an approval. A deliberate re-versioning workflow is the only
     * supported way to replace approved evidence.
     */
    public static void write(Path repositoryRoot, Map<String, byte[]> assets) {
        requireNoDecidedReviews(repositoryRoot);
        for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
            Path file = repositoryRoot.resolve(entry.getKey());
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    /** Refuses when a committed review ledger already carries an approved or rejected decision. */
    public static void requireNoDecidedReviews(Path repositoryRoot) {
        Path reviews = corpusRoot(repositoryRoot).resolve("reviews");
        if (!Files.isDirectory(reviews)) {
            return;
        }
        List<String> decided = new ArrayList<>();
        try (var stream = Files.list(reviews)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".json")).toList()) {
                CaseReviewEvidence review = CANONICAL_JSON.read(
                        Files.readString(file, StandardCharsets.UTF_8), CaseReviewEvidence.class);
                for (CaseReviewEntry entry : review.reviews()) {
                    if (entry.isDecided()) {
                        decided.add(file.getFileName() + " (" + entry.role() + ")");
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (ContractException exception) {
            throw new IllegalStateException(
                    "A committed review ledger is not readable; refusing to overwrite it", exception);
        }
        if (!decided.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to regenerate: committed review ledgers already carry real decisions: " + decided);
        }
    }

    /**
     * Compares the generated assets with the committed files, without rewriting anything.
     *
     * <p>Returns one human-readable difference per problem: a missing file, an extra committed file or a byte
     * difference. An empty list means the committed corpus is exactly the generated corpus.
     */
    public static List<String> differences(Path repositoryRoot, Map<String, byte[]> assets) {
        List<String> differences = new ArrayList<>();
        Path root = corpusRoot(repositoryRoot);
        for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
            Path file = repositoryRoot.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                differences.add("missing: " + relative(root, file));
                continue;
            }
            byte[] committed;
            try {
                committed = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            if (!java.util.Arrays.equals(committed, entry.getValue())) {
                differences.add("differs: " + relative(root, file));
            }
        }
        if (Files.isDirectory(root)) {
            TreeSet<String> generatedSuffixes = new TreeSet<>();
            for (String key : assets.keySet()) {
                generatedSuffixes.add(key.substring(ReferenceCorpusVersions.CORPUS_ROOT.length() + 1)
                        .replace('\\', '/'));
            }
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String suffix = root.relativize(file).toString().replace('\\', '/');
                    if (!generatedSuffixes.contains(suffix)) {
                        differences.add("unexpected: " + suffix);
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return List.copyOf(differences);
    }

    /** The asset count by top-level directory under the corpus root, for reporting. */
    public static Map<String, Integer> countsByDirectory(Map<String, byte[]> assets) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : assets.keySet()) {
            String suffix = path.substring(ReferenceCorpusVersions.CORPUS_ROOT.length() + 1);
            String directory = suffix.contains("/") ? suffix.substring(0, suffix.indexOf('/')) : "(root)";
            counts.merge(directory, 1, Integer::sum);
        }
        return counts;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}