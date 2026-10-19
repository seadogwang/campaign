package com.company.campaign.readiness.migration;

import com.company.campaign.readiness.ReadinessErrorCodes;
import com.company.campaign.readiness.ReadinessException;
import com.company.campaign.readiness.ReadinessVersions;
import com.company.campaign.shared.canonical.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The pinned contract for the T25 ordered, expand-only migration set.
 *
 * <p>It does three things and refuses to do anything else:
 * <ul>
 *   <li>it <strong>pins</strong> the exact name, order and checksum of every committed migration, so a
 *       renamed, reordered or edited asset is refused rather than replayed;</li>
 *   <li>it <strong>refuses destructive statements</strong> — drops, truncations, data rewrites, column type
 *       changes and renames — so an applied migration set is always a superset of the previous one;</li>
 *   <li>it <strong>requires idempotence on an empty database</strong>, so every {@code CREATE} is guarded and
 *       the set is safe to apply to a database that has never seen it.</li>
 * </ul>
 *
 * <p>It is Flyway-compatible naming and ordering only. This repository deliberately does not depend on
 * Flyway, and nothing here claims a migration was applied to a real PostgreSQL instance: that remains an
 * honest blocker until a real run happens.
 */
public final class MigrationCatalog {

    /** The version of the pinned migration contract. */
    public static final String VERSION = ReadinessVersions.MIGRATION_SET_VERSION;

    /** The repository-relative directory the migration assets live in. */
    public static final String DIRECTORY_REFERENCE = "src/main/resources/db/migration";

    /** The classpath-relative directory the same assets are packaged under. */
    public static final String CLASSPATH_DIRECTORY = "db/migration";

    /**
     * The pinned asset set, in order. Every checksum is the SHA-256 of the committed file's exact bytes.
     */
    private static final List<MigrationAsset> PINNED = List.of(
            new MigrationAsset(
                    "V1__readiness_scope_and_evidence.sql",
                    1,
                    "",
                    new ContentHash("sha256:60beaf381506a596d89d71b3a2b6092e575a5fcfbc23962f226c161d1c0113a6")),
            new MigrationAsset(
                    "V2__readiness_assessment.sql",
                    2,
                    "",
                    new ContentHash("sha256:c469f41d28700b7d4065612dd1e2713d83501b2754b19afb78f996125bb4a7e7")),
            new MigrationAsset(
                    "V3__readiness_audit.sql",
                    3,
                    "",
                    new ContentHash("sha256:e6b2a959e9bae2375bd470f7f5a3d03295de4cb0fa2f7da4bc670cbc0e68d690")));

    /**
     * Fragments that would make a migration destructive or non-expand-only. A migration may add objects and
     * add columns; it may not remove, rewrite or retype anything a previous version created.
     */
    private static final List<String> DESTRUCTIVE_FRAGMENTS = List.of(
            "DROP TABLE",
            "DROP COLUMN",
            "DROP INDEX",
            "DROP CONSTRAINT",
            "DROP SCHEMA",
            "DROP DATABASE",
            "DROP TYPE",
            "TRUNCATE",
            "DELETE FROM",
            "ALTER COLUMN",
            "RENAME TO",
            "RENAME COLUMN",
            "SET NOT NULL",
            "DROP NOT NULL",
            "SET DATA TYPE",
            "UPDATE ");

    /** Statements that must carry an idempotence guard so the set is safe for an empty database. */
    private static final List<String> GUARDED_STATEMENTS = List.of("CREATE TABLE", "CREATE INDEX");

    private MigrationCatalog() {
    }

    /** The pinned asset set, in order, with empty statement bodies. */
    public static List<MigrationAsset> pinned() {
        return PINNED;
    }

    /** The pinned file names, in order. */
    public static List<String> pinnedNames() {
        return PINNED.stream().map(MigrationAsset::name).toList();
    }

    /** Reads and verifies the migration set from a repository-relative directory. */
    public static List<MigrationAsset> loadFromDirectory(Path directory) {
        Path root = directory == null ? null : directory.toAbsolutePath().normalize();
        if (root == null || !Files.isDirectory(root)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "The readiness migration directory does not exist.");
        }
        Set<String> expected = new LinkedHashSet<>(pinnedNames());
        Set<String> actual;
        try (Stream<Path> files = Files.list(root)) {
            actual = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException exception) {
            throw unreadable(exception);
        }
        if (!expected.equals(actual)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "The readiness migration directory does not contain exactly the pinned asset set.");
        }
        List<MigrationAsset> loaded = new ArrayList<>(PINNED.size());
        for (MigrationAsset pinned : PINNED) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(root.resolve(pinned.name()));
            } catch (IOException exception) {
                throw unreadable(exception);
            }
            loaded.add(new MigrationAsset(
                    pinned.name(),
                    pinned.version(),
                    new String(bytes, StandardCharsets.UTF_8),
                    ContentHash.sha256(bytes)));
        }
        return verified(loaded);
    }

    /** Reads and verifies the migration set from the packaged classpath. */
    public static List<MigrationAsset> loadFromClasspath(ClassLoader classLoader) {
        ClassLoader loader = classLoader == null
                ? MigrationCatalog.class.getClassLoader()
                : classLoader;
        List<MigrationAsset> loaded = new ArrayList<>(PINNED.size());
        for (MigrationAsset pinned : PINNED) {
            byte[] bytes;
            try (InputStream stream =
                         loader.getResourceAsStream(CLASSPATH_DIRECTORY + "/" + pinned.name())) {
                if (stream == null) {
                    throw new ReadinessException(
                            ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                            "A pinned readiness migration asset is not present on the classpath.");
                }
                bytes = stream.readAllBytes();
            } catch (IOException exception) {
                throw unreadable(exception);
            }
            loaded.add(new MigrationAsset(
                    pinned.name(),
                    pinned.version(),
                    new String(bytes, StandardCharsets.UTF_8),
                    ContentHash.sha256(bytes)));
        }
        return verified(loaded);
    }

    /**
     * Verifies one already-loaded asset set against the pinned contract: exact name and order, exact checksum,
     * self-consistency, no destructive fragment and a guard on every {@code CREATE}.
     */
    public static List<MigrationAsset> verified(List<MigrationAsset> assets) {
        if (assets == null || assets.size() != PINNED.size()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "The readiness migration set does not contain exactly the pinned number of assets.");
        }
        for (int index = 0; index < PINNED.size(); index++) {
            MigrationAsset pinned = PINNED.get(index);
            MigrationAsset actual = assets.get(index);
            if (!pinned.name().equals(actual.name()) || pinned.version() != actual.version()) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                        "A readiness migration asset is out of the pinned order.");
            }
            if (!pinned.checksum().equals(actual.checksum())) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_MIGRATION_CHECKSUM_MISMATCH,
                        "A readiness migration asset does not match its pinned checksum.");
            }
            if (!actual.hasBody() || !actual.selfConsistent()) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                        "A readiness migration asset is empty or contradicts its own checksum.");
            }
            requireExpandOnly(actual);
            requireSafeOnEmptyDatabase(actual);
        }
        return List.copyOf(assets);
    }

    /** Refuses a migration that would remove, rewrite or retype anything a previous version created. */
    public static void requireExpandOnly(MigrationAsset asset) {
        String normalised = statementsOnly(asset.sql());
        for (String fragment : DESTRUCTIVE_FRAGMENTS) {
            if (normalised.contains(fragment)) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_MIGRATION_DESTRUCTIVE,
                        "A readiness migration asset contains a destructive statement.",
                        java.util.Map.of("asset", asset.name(), "fragment", fragment));
            }
        }
    }

    /** Refuses a migration whose {@code CREATE} statements are not guarded, so it is safe on an empty database. */
    public static void requireSafeOnEmptyDatabase(MigrationAsset asset) {
        String normalised = statementsOnly(asset.sql());
        for (String guarded : GUARDED_STATEMENTS) {
            int index = normalised.indexOf(guarded);
            while (index >= 0) {
                String following = normalised.substring(index + guarded.length()).stripLeading();
                if (!following.startsWith("IF NOT EXISTS")) {
                    throw new ReadinessException(
                            ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                            "A readiness migration must guard every CREATE so it is safe on an empty database.",
                            java.util.Map.of("asset", asset.name(), "statement", guarded));
                }
                index = normalised.indexOf(guarded, index + guarded.length());
            }
        }
    }

    /** Verifies a caller-supplied asset set is strictly ordered and free of duplicate versions. */
    public static void requireOrdered(List<MigrationAsset> assets) {
        List<MigrationAsset> ordered = new ArrayList<>(assets == null ? List.of() : assets);
        ordered.sort(Comparator.comparingInt(MigrationAsset::version));
        Set<Integer> versions = new LinkedHashSet<>();
        for (MigrationAsset asset : ordered) {
            if (!versions.add(asset.version())) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                        "Two readiness migration assets declare the same version.");
            }
        }
        if (!ordered.equals(assets)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                    "The readiness migration assets are not in ascending version order.");
        }
    }

    /** Every pinned migration statement, in order, for a host that applies them itself. */
    public static List<String> pinnedStatements() {
        List<MigrationAsset> loaded = loadFromClasspath(MigrationCatalog.class.getClassLoader());
        List<String> statements = new ArrayList<>(loaded.size());
        for (MigrationAsset asset : loaded) {
            statements.addAll(Arrays.stream(asset.sql().split(";\\s*\\n"))
                    .map(String::strip)
                    .filter(statement -> !statement.isEmpty())
                    .toList());
        }
        return List.copyOf(statements);
    }

    private static String statementsOnly(String sql) {
        return sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .toUpperCase(Locale.ROOT);
    }

    private static ReadinessException unreadable(IOException cause) {
        return new ReadinessException(
                ReadinessErrorCodes.READINESS_MIGRATION_INVALID,
                "A readiness migration asset cannot be read.",
                java.util.Map.of(),
                cause);
    }
}
