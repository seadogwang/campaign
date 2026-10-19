package com.company.campaign.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the unified business migration set.
 *
 * <p>The unified datasource runs every business slice through one Flyway runtime, so the unified location must
 * expose one globally unique version sequence. Before this guard existed, {@code Start-LocalCampaign} merged
 * four legacy directories — each carrying its own {@code V1} — into one runtime and failed with Flyway's
 * {@code Found more than one migration with version 1}. This test pins the three properties that make the
 * unified run safe: one committed location, globally unique versions, and idempotent {@code CREATE} guards so
 * the set can be applied on top of a boundary that already carries a legacy slice.
 */
class CampaignUnifiedMigrationTest {

    private static final Path UNIFIED_DIRECTORY =
            Path.of("src", "main", "resources", "db", "campaign-unified-migration");

    private static final Pattern VERSION = Pattern.compile("^V([0-9]+)__(.+\\.sql)$");

    /** Every legacy directory that must stay byte-for-byte in place for its dedicated component contract. */
    private static final Map<Path, List<String>> LEGACY_ASSETS = Map.of(
            Path.of("src", "main", "resources", "db", "migration"),
            List.of("V1__readiness_scope_and_evidence.sql", "V2__readiness_assessment.sql",
                    "V3__readiness_audit.sql"),
            Path.of("src", "main", "resources", "db", "campaign-conversation-migration"),
            List.of("V1__campaign_conversation_store.sql", "V2__campaign_business_resource_catalog.sql"),
            Path.of("src", "main", "resources", "db", "campaign-production-membership-migration"),
            List.of("V1__campaign_production_membership.sql"),
            Path.of("src", "main", "resources", "db", "campaign-production-audit-migration"),
            List.of("V1__campaign_production_audit_ledger.sql"));

    @Test
    void theUnifiedRunnerPointsAtExactlyOneCommittedLocation() {
        assertThat(CampaignDatasourceProperties.MIGRATION_LOCATIONS)
                .isEqualTo("classpath:db/campaign-unified-migration");
        assertThat(CampaignDatasourceProperties.MIGRATION_LOCATIONS.split(","))
                .as("a single Flyway runtime must receive a single location, never the merged legacy set")
                .hasSize(1);
    }

    @Test
    void theUnifiedLocationCarriesGloballyUniqueFlywayVersions() {
        Map<Integer, String> byVersion = new LinkedHashMap<>();
        for (String name : fileNames(UNIFIED_DIRECTORY)) {
            Matcher matcher = VERSION.matcher(name);
            assertThat(matcher.matches())
                    .as("unified asset %s is a versioned migration name", name)
                    .isTrue();
            int version = Integer.parseInt(matcher.group(1));
            assertThat(byVersion)
                    .as("version %s may only appear once across the unified set", version)
                    .doesNotContainKey(version);
            byVersion.put(version, name);
        }
        assertThat(byVersion).hasSize(10);
        // The readiness slice keeps V1..V3 so the bounded storage probe's pinned versions stay valid, while the
        // other slices carry higher, globally unique versions.
        assertThat(byVersion.keySet()).containsExactlyInAnyOrder(1, 2, 3, 101, 102, 103, 201, 301, 401, 402);
    }

    @Test
    void everyUnifiedCreateIsGuardedForAnIdempotentReplay() {
        for (String name : fileNames(UNIFIED_DIRECTORY)) {
            String sql = read(UNIFIED_DIRECTORY.resolve(name)).toLowerCase(java.util.Locale.ROOT);
            for (String statement : List.of("create table ", "create index ", "create unique index ")) {
                int index = sql.indexOf(statement);
                while (index >= 0) {
                    int after = index + statement.length();
                    assertThat(sql.substring(after).stripLeading())
                            .as("%s guards every %s", name, statement.strip())
                            .startsWith("if not exists");
                    index = sql.indexOf(statement, after);
                }
            }
        }
    }

    @Test
    void theUnifiedRunnerRecordsHistoryInItsOwnDedicatedTable() {
        assertThat(CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE)
                .isEqualTo("flyway_campaign_unified_schema_history");
        assertThat(CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE)
                .isNotEqualTo("flyway_schema_history");

        // The unified runner owns its own history table, so it never validates or migrates against a legacy
        // readiness/conversation/membership/audit flyway_schema_history in the same schema.
        String configuration = read(Path.of(
                "src", "main", "java", "com", "company", "campaign", "persistence",
                "CampaignDatasourceConfiguration.java"));
        assertThat(configuration)
                .contains(".table(CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE)")
                .contains(".baselineOnMigrate(true)")
                .contains(".baselineVersion(\"0\")")
                .doesNotContain("ignoreMigrationPatterns");

        // The bounded storage probe reads that same dedicated table, so it still verifies the unified history.
        String probe = read(Path.of(
                "src", "main", "java", "com", "company", "campaign", "readiness", "postgres",
                "PostgresReadinessStorageProbe.java"));
        assertThat(probe).contains("CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE");
    }

    @Test
    void everyLegacyDedicatedAssetSetIsPreservedUnchanged() {
        for (Map.Entry<Path, List<String>> entry : LEGACY_ASSETS.entrySet()) {
            assertThat(fileNames(entry.getKey()))
                    .as("legacy asset set %s keeps its exact committed files", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> fileNames(Path directory) {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString).forEach(names::add);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return names;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
