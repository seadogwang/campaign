package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic loader for the T19 capability evaluation corpus under {@code evaluation/capabilities/t19-v1}.
 *
 * <p>It answers exactly one question: <em>are these files what the manifest says they are?</em> Every
 * reference is resolved beneath the configured evaluation root with a hostile lexical check, every asset is
 * re-proved to be its own canonical serialization, and every declared content hash is re-derived from the
 * bytes it pins. The manifest's own hash is re-derived the same way, so a hand-edited manifest or fixture
 * cannot pass as the reviewed artifact.
 *
 * <p>It answers that question and no other. Whether a corpus covers every pack, and whether a fixture's
 * expectation matches what a pack does, is the composition and pack tests' business: this loader must be able
 * to load a corpus so that coverage can be judged, without itself becoming a second validator.
 *
 * <p>Nothing here reads a clock, generates an identifier, touches the network or depends on the default
 * locale: the same files always produce the same corpus.
 */
public final class T19EvaluationLoader {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
    };

    /** The manifest field holding the manifest's own content hash; it is excluded from its own hash material. */
    private static final String MANIFEST_HASH_FIELD = "manifestHash";

    private final Path repositoryRoot;
    private final Path evaluationRoot;

    public T19EvaluationLoader(Path repositoryRoot) {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
        Path evaluation = root.resolve(T19EvaluationVersions.EVALUATION_ROOT_REFERENCE).normalize();
        if (!evaluation.startsWith(root) || evaluation.equals(root)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "The T19 evaluation root is not beneath the repository root.");
        }
        this.repositoryRoot = root;
        this.evaluationRoot = evaluation;
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public Path evaluationRoot() {
        return evaluationRoot;
    }

    public String defaultManifestReference() {
        return T19EvaluationVersions.MANIFEST_REFERENCE;
    }

    /** Loads and verifies the committed manifest. */
    public T19EvaluationManifest loadManifest() {
        return loadManifest(defaultManifestReference());
    }

    /** Loads and verifies a manifest at an explicit repository-relative reference. */
    public T19EvaluationManifest loadManifest(String manifestReference) {
        Path file = resolve(manifestReference);
        byte[] bytes = readBytes(file);
        Map<String, Object> full = strictObject(bytes);
        requireCanonical(bytes, full);
        requireManifestHash(full);
        T19EvaluationManifest manifest = read(bytes, T19EvaluationManifest.class);
        manifest.requireSupportedSchemaVersion();
        return manifest;
    }

    /** Loads and verifies the whole committed corpus, manifest first. */
    public T19EvaluationCorpus loadCorpus() {
        T19EvaluationManifest manifest = loadManifest();
        List<T19EvaluationFixtureDocument> documents = new ArrayList<>(manifest.packs().size());
        for (T19PackAssetReference reference : manifest.packs()) {
            documents.add(loadDocument(reference));
        }
        return new T19EvaluationCorpus(manifest, documents, loadCompositions(manifest.compositions()));
    }

    private T19EvaluationFixtureDocument loadDocument(T19PackAssetReference reference) {
        byte[] bytes = verifyAsset(reference.fixturesPath(), reference.fixturesHash());
        T19EvaluationFixtureDocument document = read(bytes, T19EvaluationFixtureDocument.class);
        if (!document.assetId().equals(reference.assetId())
                || !document.assetVersion().equals(reference.assetVersion())
                || !document.capabilityCode().equals(reference.capabilityCode())
                || !document.contractVersion().equals(reference.contractVersion())) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "A fixture document's declared identity disagrees with its manifest entry.");
        }
        return document;
    }

    private T19CompositionDocument loadCompositions(T19CompositionAssetReference reference) {
        byte[] bytes = verifyAsset(reference.path(), reference.contentHash());
        T19CompositionDocument document = read(bytes, T19CompositionDocument.class);
        if (!document.assetId().equals(reference.assetId())
                || !document.assetVersion().equals(reference.assetVersion())) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_IDENTITY_MISMATCH,
                    "A composition document's declared identity disagrees with its manifest entry.");
        }
        return document;
    }

    // ------------------------------------------------------------------ asset verification

    /**
     * Resolves one reference, reads it, re-proves its canonical form and re-derives its pinned hash.
     */
    private byte[] verifyAsset(String reference, ContentHash expectedHash) {
        Path file = resolve(reference);
        byte[] bytes = readBytes(file);
        requireCanonical(bytes, strictObject(bytes));
        ContentHash actual = ContentHash.sha256(bytes);
        if (!expectedHash.equals(actual)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_HASH_MISMATCH,
                    "An evaluation asset's declared hash is not the hash of its bytes.");
        }
        return bytes;
    }

    /** Re-derives the manifest's own hash from the manifest with the hash field absent. */
    private static void requireManifestHash(Map<String, Object> full) {
        Object declared = full.get(MANIFEST_HASH_FIELD);
        if (!(declared instanceof String text)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "The evaluation manifest declares no content hash.");
        }
        ContentHash pin;
        try {
            pin = new ContentHash(text);
        } catch (RuntimeException exception) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "The evaluation manifest declares a malformed content hash.");
        }
        Map<String, Object> material = new java.util.LinkedHashMap<>(full);
        material.remove(MANIFEST_HASH_FIELD);
        if (!pin.equals(CANONICAL_JSON.hash(material))) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.MANIFEST_HASH_MISMATCH,
                    "The evaluation manifest's declared hash is not the hash of its content.");
        }
    }

    private static void requireCanonical(byte[] bytes, Map<String, Object> parsed) {
        if (!Arrays.equals(bytes, CANONICAL_JSON.write(parsed))) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "An evaluation asset is not its own canonical serialization.");
        }
    }

    // ------------------------------------------------------------------ path and I/O

    /**
     * Resolves one repository-relative reference beneath the configured evaluation root, refusing absolute
     * forms, backslashes, parent segments and any path that escapes the root.
     */
    private Path resolve(String reference) {
        if (reference == null || reference.isBlank() || reference.indexOf('\\') >= 0) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_MALFORMED,
                    "An evaluation reference must be a non-empty forward-slash relative path.");
        }
        Path raw;
        try {
            raw = Path.of(reference);
        } catch (InvalidPathException exception) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_MALFORMED,
                    "An evaluation reference is not a well-formed path.");
        }
        if (raw.isAbsolute() || raw.getRoot() != null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_MALFORMED,
                    "An evaluation reference must be relative.");
        }
        for (Path segment : raw) {
            String text = segment.toString();
            if ("..".equals(text) || ".".equals(text) || text.isEmpty()) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.PATH_MALFORMED,
                        "An evaluation reference must be normalized and free of parent segments.");
            }
        }
        Path resolved = repositoryRoot.resolve(raw).normalize();
        if (!resolved.startsWith(evaluationRoot)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.PATH_OUTSIDE_ROOT,
                    "An evaluation reference resolves outside the evaluation root.");
        }
        return resolved;
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_UNREADABLE,
                    "An evaluation asset could not be read.", Map.of(), exception);
        }
    }

    private static Map<String, Object> strictObject(byte[] bytes) {
        try {
            return CANONICAL_JSON.read(text(bytes), OBJECT_TYPE);
        } catch (RuntimeException exception) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "An evaluation asset is not a well-formed, canonical JSON object.", Map.of(), exception);
        }
    }

    private static <T> T read(byte[] bytes, Class<T> type) {
        try {
            return CANONICAL_JSON.read(text(bytes), type);
        } catch (RuntimeException exception) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "An evaluation asset is not a well-formed T19 evaluation contract.", Map.of(), exception);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}