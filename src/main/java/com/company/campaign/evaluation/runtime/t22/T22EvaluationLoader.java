package com.company.campaign.evaluation.runtime.t22;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The strict loader for the frozen T22 Runtime evaluation asset set under {@code evaluation/runtime/t22-v1}.
 *
 * <p>Everything is proved before anything is returned: the directory holds exactly the two frozen files, both
 * are their own strict canonical serialization, the manifest's declared self-hash is the canonical hash of the
 * manifest without that field, and the cases document's bytes hash to exactly the cases hash the manifest pins.
 * A tampered case, an extra file, a stray whitespace byte, an unknown field and a manifest whose self-hash was
 * not updated are all refused with a bounded machine code.
 *
 * <p>The corpus is development evidence: both documents must declare {@code productionGateEvidence=false}, and
 * the records refuse to represent {@code true}, so a T22 asset can never be relabelled as production Gate
 * evidence.
 */
public final class T22EvaluationLoader {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String ASSET_VERSION = "t22-v1";
    public static final String MANIFEST_ASSET_ID = "runtime-t22-manifest";
    public static final String CASES_ASSET_ID = "runtime-t22-cases";
    public static final String ROOT_REFERENCE = "evaluation/runtime/t22-v1";
    public static final String MANIFEST_REFERENCE = ROOT_REFERENCE + "/manifest.json";
    public static final String CASES_REFERENCE = ROOT_REFERENCE + "/cases.json";

    private static final Set<String> EXPECTED_FILES = Set.of("manifest.json", "cases.json");
    private static final String MANIFEST_HASH_FIELD = "manifestHash";
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final Path repositoryRoot;
    private final Path evaluationRoot;

    public T22EvaluationLoader(Path repositoryRoot) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        this.evaluationRoot = this.repositoryRoot.resolve(ROOT_REFERENCE).normalize();
        if (!evaluationRoot.startsWith(this.repositoryRoot) || evaluationRoot.equals(this.repositoryRoot)) {
            throw invalidSet();
        }
    }

    /** The repository root this loader resolves its assets against. */
    public Path repositoryRoot() {
        return repositoryRoot;
    }

    /** Loads and fully verifies the frozen corpus. */
    public T22EvaluationCorpus loadCorpus() {
        requireExactFileSet();
        byte[] manifestBytes = read(repositoryRoot.resolve(MANIFEST_REFERENCE));
        Map<String, Object> manifestObject = strictObject(manifestBytes);
        requireCanonical(manifestBytes, manifestObject);
        requireManifestHash(manifestObject);
        T22EvaluationManifest manifest = strictRead(manifestBytes, T22EvaluationManifest.class);

        byte[] casesBytes = read(repositoryRoot.resolve(CASES_REFERENCE));
        Map<String, Object> casesObject = strictObject(casesBytes);
        requireCanonical(casesBytes, casesObject);
        T22EvaluationCases cases = strictRead(casesBytes, T22EvaluationCases.class);
        if (!manifest.casesHash().equals(ContentHash.sha256(casesBytes))) {
            throw new T22EvaluationException(
                    T22EvaluationErrorCodes.ASSET_HASH_MISMATCH,
                    "The T22 cases asset does not match its pinned hash.");
        }
        return new T22EvaluationCorpus(manifest, cases);
    }

    private void requireExactFileSet() {
        try (var files = Files.list(evaluationRoot)) {
            Set<String> actual = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
            if (!EXPECTED_FILES.equals(actual)) {
                throw invalidSet();
            }
        } catch (IOException exception) {
            throw new T22EvaluationException(
                    T22EvaluationErrorCodes.ASSET_UNREADABLE,
                    "The T22 evaluation asset set cannot be read.", exception);
        }
    }

    private static void requireManifestHash(Map<String, Object> full) {
        Object declared = full.get(MANIFEST_HASH_FIELD);
        if (!(declared instanceof String text)) {
            throw manifestHashMismatch();
        }
        ContentHash expected;
        try {
            expected = new ContentHash(text);
        } catch (RuntimeException exception) {
            throw manifestHashMismatch();
        }
        Map<String, Object> material = new LinkedHashMap<>(full);
        material.remove(MANIFEST_HASH_FIELD);
        if (!expected.equals(JSON.hash(material))) {
            throw manifestHashMismatch();
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new T22EvaluationException(
                    T22EvaluationErrorCodes.ASSET_UNREADABLE,
                    "A T22 evaluation asset cannot be read.", exception);
        }
    }

    private static Map<String, Object> strictObject(byte[] bytes) {
        try {
            return JSON.read(new String(bytes, StandardCharsets.UTF_8), OBJECT);
        } catch (RuntimeException exception) {
            throw nonCanonical(exception);
        }
    }

    private static <T> T strictRead(byte[] bytes, Class<T> type) {
        try {
            return JSON.read(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (RuntimeException exception) {
            throw nonCanonical(exception);
        }
    }

    private static void requireCanonical(byte[] bytes, Map<String, Object> parsed) {
        if (!Arrays.equals(bytes, JSON.write(parsed))) {
            throw nonCanonical(null);
        }
    }

    private static T22EvaluationException nonCanonical(Throwable cause) {
        return new T22EvaluationException(
                T22EvaluationErrorCodes.ASSET_NON_CANONICAL,
                "A T22 evaluation asset is not strict canonical JSON.", cause);
    }

    private static T22EvaluationException invalidSet() {
        return new T22EvaluationException(
                T22EvaluationErrorCodes.ASSET_SET_INVALID,
                "The T22 evaluation directory must contain exactly the frozen asset set.");
    }

    private static T22EvaluationException manifestHashMismatch() {
        return new T22EvaluationException(
                T22EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                "The T22 evaluation manifest hash is invalid.");
    }

    @Override
    public String toString() {
        return "T22EvaluationLoader[reference=" + ROOT_REFERENCE + ']';
    }
}