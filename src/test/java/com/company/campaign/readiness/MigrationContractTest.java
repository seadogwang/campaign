package com.company.campaign.readiness;

import com.company.campaign.readiness.migration.MigrationAsset;
import com.company.campaign.readiness.migration.MigrationCatalog;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T25 migration contract tests: the committed set is exactly the pinned, ordered, checksum-stable,
 * expand-only and empty-database-safe set, and every way of deviating from it is refused with a stable code.
 *
 * <p>These tests read files and verify them. They never open a database connection, and nothing here claims a
 * migration was applied to a real PostgreSQL instance — that remains an honest blocker until a real run
 * happens.
 */
class MigrationContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final Path MIGRATION_DIRECTORY =
            REPOSITORY_ROOT.resolve(MigrationCatalog.DIRECTORY_REFERENCE);

    @Test
    void theCommittedMigrationSetIsExactlyThePinnedOrderedSet() {
        List<MigrationAsset> loaded = MigrationCatalog.loadFromDirectory(MIGRATION_DIRECTORY);

        assertThat(loaded).hasSize(3);
        assertThat(loaded.stream().map(MigrationAsset::name).toList())
                .isEqualTo(MigrationCatalog.pinnedNames());
        assertThat(loaded.stream().map(MigrationAsset::version).toList())
                .containsExactly(1, 2, 3);
        assertThat(loaded).allSatisfy(asset -> {
            assertThat(asset.hasBody()).isTrue();
            assertThat(asset.selfConsistent()).isTrue();
        });
        assertThat(MigrationCatalog.pinned()).hasSize(3);
        assertThat(MigrationCatalog.VERSION).isEqualTo("campaign-readiness-migrations/1.0");
    }

    @Test
    void everyPinnedChecksumIsTheChecksumOfTheCommittedBytes() throws IOException {
        for (MigrationAsset pinned : MigrationCatalog.pinned()) {
            byte[] bytes = Files.readAllBytes(MIGRATION_DIRECTORY.resolve(pinned.name()));
            assertThat(pinned.checksum()).isEqualTo(ContentHash.sha256(bytes));
        }
    }

    @Test
    void theSameAssetsVerifyFromTheClasspathAndYieldTheirStatements() {
        List<MigrationAsset> fromClasspath = MigrationCatalog.loadFromClasspath(
                MigrationCatalog.class.getClassLoader());

        assertThat(fromClasspath).hasSize(3);
        assertThat(fromClasspath.stream().map(MigrationAsset::checksum).toList())
                .isEqualTo(MigrationCatalog.pinned().stream().map(MigrationAsset::checksum).toList());
        assertThat(MigrationCatalog.pinnedStatements()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(MigrationCatalog.pinnedStatements()).allSatisfy(statement ->
                assertThat(statement).isNotBlank());
    }

    @Test
    void aDirectoryThatIsNotTheCommittedSetIsRefused() {
        assertThatThrownBy(() -> MigrationCatalog.loadFromDirectory(
                REPOSITORY_ROOT.resolve("src/main/resources/db")))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_INVALID));

        assertThatThrownBy(() -> MigrationCatalog.loadFromDirectory(
                REPOSITORY_ROOT.resolve("does-not-exist")))
                .isInstanceOf(ReadinessException.class);
    }

    @Test
    void aDestructiveStatementIsRefused() {
        assertDestructive("V9__drop_table.sql", "DROP TABLE readiness_evidence;");
        assertDestructive("V9__drop_column.sql", "ALTER TABLE readiness_evidence DROP COLUMN subject;");
        assertDestructive("V9__truncate.sql", "TRUNCATE readiness_evidence;");
        assertDestructive("V9__delete.sql", "DELETE FROM readiness_evidence WHERE 1 = 1;");
        assertDestructive("V9__retype.sql", "ALTER TABLE readiness_evidence ALTER COLUMN status SET DATA TYPE text;");
        assertDestructive("V9__rewrite.sql", "UPDATE readiness_evidence SET status = 'INVALID';");
        assertDestructive("V9__multiline_drop.sql", "DROP\nTABLE readiness_evidence;");
    }

    private static void assertDestructive(String name, String sql) {
        MigrationAsset asset = MigrationAsset.of(name, sql);
        assertThatThrownBy(() -> MigrationCatalog.requireExpandOnly(asset))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_DESTRUCTIVE));
    }

    @Test
    void anUnguardedCreateIsRefusedSoTheSetStaysSafeOnAnEmptyDatabase() {
        MigrationAsset unguardedTable = MigrationAsset.of(
                "V9__create_table.sql", "CREATE TABLE readiness_extra (id BIGINT);");
        MigrationAsset unguardedIndex = MigrationAsset.of(
                "V9__create_index.sql", "CREATE INDEX ix_extra ON readiness_extra (id);");

        assertThatThrownBy(() -> MigrationCatalog.requireSafeOnEmptyDatabase(unguardedTable))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_INVALID));
        assertThatThrownBy(() -> MigrationCatalog.requireSafeOnEmptyDatabase(unguardedIndex))
                .isInstanceOf(ReadinessException.class);

        MigrationAsset guarded = MigrationAsset.of(
                "V9__create_guarded.sql", "CREATE TABLE IF NOT EXISTS readiness_extra (id BIGINT);");
        MigrationCatalog.requireSafeOnEmptyDatabase(guarded);
        MigrationCatalog.requireExpandOnly(guarded);
    }

    @Test
    void aChangedChecksumIsRefusedRatherThanReplayed() {
        List<MigrationAsset> loaded = MigrationCatalog.loadFromDirectory(MIGRATION_DIRECTORY);
        MigrationAsset pinned = loaded.get(1);
        MigrationAsset edited = MigrationAsset.of(pinned.name(), pinned.sql() + "\n-- edited");

        List<MigrationAsset> editedSet = List.of(loaded.get(0), edited, loaded.get(2));

        assertThatThrownBy(() -> MigrationCatalog.verified(editedSet))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_CHECKSUM_MISMATCH));
    }

    @Test
    void anEmptyOrShortAssetSetIsRefused() {
        assertThatThrownBy(() -> MigrationCatalog.verified(List.of()))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_INVALID));
    }

    @Test
    void anOutOfOrderOrDuplicatedVersionSetIsRefused() {
        List<MigrationAsset> loaded = MigrationCatalog.loadFromDirectory(MIGRATION_DIRECTORY);
        List<MigrationAsset> reversed = List.of(loaded.get(2), loaded.get(1), loaded.get(0));

        assertThatThrownBy(() -> MigrationCatalog.requireOrdered(reversed))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_MIGRATION_INVALID));

        MigrationAsset duplicate = MigrationAsset.of("V1__another_first.sql", "CREATE TABLE IF NOT EXISTS x (a int);");
        assertThatThrownBy(() -> MigrationCatalog.requireOrdered(List.of(loaded.get(0), duplicate)))
                .isInstanceOf(ReadinessException.class);
    }

    @Test
    void aMalformedOrMismatchedMigrationNameIsRefused() {
        assertThatThrownBy(() -> MigrationAsset.of("readiness.sql", "CREATE TABLE IF NOT EXISTS x (a int);"))
                .isInstanceOf(ReadinessException.class);
        assertThatThrownBy(() -> MigrationAsset.of("V1__Bad-Name.sql", "CREATE TABLE IF NOT EXISTS x (a int);"))
                .isInstanceOf(ReadinessException.class);
        assertThatThrownBy(() -> new MigrationAsset(
                "V2__version_mismatch.sql", 3, "CREATE TABLE IF NOT EXISTS x (a int);",
                ContentHash.sha256("x".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(ReadinessException.class);
    }

    @Test
    void theReadinessSliceItselfIsNeverProductionGateEvidence() {
        assertThat(ReadinessVersions.PRODUCTION_GATE_EVIDENCE).isFalse();
    }
}
