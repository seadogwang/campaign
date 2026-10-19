package com.company.campaign.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that handwritten JDBC persistence is confined to the explicit allowlist.
 *
 * <p>After the MyBatis-Plus migration, the only production code that may import {@code java.sql} or
 * {@code javax.sql.DataSource} and execute raw SQL is:
 *
 * <ul>
 *   <li>{@code PostgresPreviewExecutionBackend} — compiler-generated dynamic SQL, read-only, cancellation</li>
 *   <li>{@code PreviewJdbcBinding} — JDBC parameter binding for the compiled pipeline</li>
 *   <li>{@code PostgresReadinessStorageProbe} — low-level connectivity/Flyway-history probing</li>
 *   <li>{@code CampaignDatasourceConfiguration} — the unified pool builder (contains JDBC imports for
 *       {@code HikariDataSource} configuration and the inner {@code PgObjectTypeHandler})</li>
 *   <li>{@code PersistenceAdapters} — null-safe conversion utilities (imports {@code java.sql.Timestamp} only)</li>
 *   <li>{@code PostgresTextArrayTypeHandler} — MyBatis type handler (imports {@code java.sql.Array} etc.)</li>
 *   <li>The Preview pool configuration ({@code PreviewPostgresConfiguration},
 *       {@code PreviewPostgresDataSourceFactory}) — the one allowed separate read-only pool</li>
 *   <li>Flyway migration SQL resources — DDL only</li>
 * </ul>
 *
 * <p>Any other production Java source that imports {@code java.sql.Connection}, {@code java.sql.PreparedStatement},
 * {@code java.sql.ResultSet}, {@code java.sql.Statement}, {@code javax.sql.DataSource#getConnection}, or
 * {@code com.zaxxer.hikari.HikariDataSource} / {@code HikariConfig} (outside the unified factory and Preview
 * factory) is a violation.
 */
class JdbcAccessAllowlistTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** Production source paths that are always allowed to use raw JDBC. */
    private static final Set<String> ALLOWED_JDBC_FILES = Set.of(
            "com/company/campaign/dev/preview/postgres/PostgresPreviewExecutionBackend.java",
            "com/company/campaign/dev/preview/postgres/PreviewJdbcBinding.java",
            "com/company/campaign/readiness/postgres/PostgresReadinessStorageProbe.java",
            "com/company/campaign/persistence/CampaignDatasourceConfiguration.java",
            "com/company/campaign/persistence/PersistenceAdapters.java",
            "com/company/campaign/persistence/PostgresTextArrayTypeHandler.java");

    /** Production source paths allowed to import HikariDataSource/HikariConfig (pool configuration). */
    private static final Set<String> ALLOWED_HIKARI_FILES = Set.of(
            "com/company/campaign/persistence/CampaignDatasourceConfiguration.java",
            "com/company/campaign/dev/preview/postgres/PreviewPostgresConfiguration.java",
            "com/company/campaign/dev/preview/postgres/PreviewPostgresDataSourceFactory.java");

    /** Test-only imports that contain java.sql but are not production code. */
    private static final Pattern TEST_PATH = Pattern.compile("src[\\\\/]test[\\\\/]");

    private static final Pattern JDBC_EXECUTION_IMPORT = Pattern.compile(
            "import\\s+java\\.sql\\.(Connection|PreparedStatement|ResultSet|Statement|SQLException|Array|Timestamp|Types);");

    private static final Pattern JDBC_GET_CONNECTION = Pattern.compile(
            "\\.getConnection\\s*\\(");

    private static final Pattern HIKARI_IMPORT = Pattern.compile(
            "import\\s+com\\.zaxxer\\.hikari\\.(HikariDataSource|HikariConfig);");

    @Test
    void noJdbcExecutionOutsideAllowlist() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
                if (ALLOWED_JDBC_FILES.contains(relative)) {
                    return;
                }
                if (TEST_PATH.matcher(file.toString()).find()) {
                    return;
                }
                try {
                    String content = Files.readString(file);
                    if (JDBC_EXECUTION_IMPORT.matcher(content).find()) {
                        violations.add(relative + " imports java.sql execution classes");
                    }
                    // Check for DataSource#getConnection() calls
                    if (JDBC_GET_CONNECTION.matcher(content).find()) {
                        // Only flag if not in a comment
                        boolean inComment = false;
                        for (String line : content.split("\n")) {
                            String trimmed = line.strip();
                            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                                continue;
                            }
                            if (JDBC_GET_CONNECTION.matcher(trimmed).find()) {
                                violations.add(relative + " calls DataSource.getConnection()");
                                break;
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        }

        assertThat(violations)
                .as("production Java sources importing java.sql execution classes outside the allowlist")
                .isEmpty();
    }

    @Test
    void noHikariPoolOutsideUnifiedAndPreviewFactories() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
                if (ALLOWED_HIKARI_FILES.contains(relative)) {
                    return;
                }
                if (TEST_PATH.matcher(file.toString()).find()) {
                    return;
                }
                try {
                    String content = Files.readString(file);
                    if (HIKARI_IMPORT.matcher(content).find()) {
                        violations.add(relative + " imports HikariDataSource/HikariConfig");
                    }
                } catch (IOException ignored) {
                }
            });
        }

        assertThat(violations)
                .as("production Java sources importing HikariDataSource/HikariConfig outside the unified " +
                    "business factory and Preview factory")
                .isEmpty();
    }
}