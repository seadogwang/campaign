package com.company.campaign.readiness.postgres;

import com.zaxxer.hikari.HikariConfig;
import org.flywaydb.core.api.logging.LogCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Focused T26 configuration tests: the opt-in PostgreSQL mode validates every bound before it connects, fails
 * closed on anything missing or out of range, and never discloses the credential or the JDBC URL.
 *
 * <p>Nothing here opens a connection, so the ordinary suite stays database-independent.
 */
class ReadinessPostgresPropertiesTest {

    private static final String URL = "jdbc:postgresql://db.internal.example:5432/campaign";
    private static final String USER = "readiness-user";
    private static final String PASSWORD = "super-secret-value";

    @AfterEach
    void clearRegisteredSecrets() {
        ReadinessFlywayLog.clearSecrets();
    }

    private static ReadinessPostgresProperties enabled() {
        ReadinessPostgresProperties properties = new ReadinessPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl(URL);
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        return properties;
    }

    @Test
    void aDisabledConfigurationIsAlwaysValidAndValidatesNothing() {
        ReadinessPostgresProperties properties = new ReadinessPostgresProperties();
        properties.setSchema("not a schema!");
        properties.setMaximumPoolSize(9_999);

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void anEnabledConfigurationWithAMissingSettingFailsClosedWithABoundedCode() {
        List<ReadinessPostgresProperties> incomplete = List.of(
                enabledWith(null, USER, PASSWORD),
                enabledWith("   ", USER, PASSWORD),
                enabledWith(URL, null, PASSWORD),
                enabledWith(URL, USER, null));

        for (ReadinessPostgresProperties properties : incomplete) {
            Throwable thrown = catchThrowable(properties::validate);
            assertThat(thrown).isInstanceOf(ReadinessPostgresConfigurationException.class);
            ReadinessPostgresConfigurationException failure =
                    (ReadinessPostgresConfigurationException) thrown;
            assertThat(failure.code()).isEqualTo(
                    ReadinessPostgresErrorCodes.READINESS_POSTGRESQL_CONFIGURATION_INVALID);
            assertThat(failure.getMessage()).doesNotContain(PASSWORD).doesNotContain(URL);
        }
    }

    @Test
    void anEnabledConfigurationWithAnOutOfBoundsSettingFailsClosed() {
        assertThatThrownBy(() -> {
            ReadinessPostgresProperties properties = enabled();
            properties.setMaximumPoolSize(0);
            properties.validate();
        }).isInstanceOf(ReadinessPostgresConfigurationException.class);

        assertThatThrownBy(() -> {
            ReadinessPostgresProperties properties = enabled();
            properties.setMaximumPoolSize(17);
            properties.validate();
        }).isInstanceOf(ReadinessPostgresConfigurationException.class);

        assertThatThrownBy(() -> {
            ReadinessPostgresProperties properties = enabled();
            properties.setMinimumIdle(5);
            properties.validate();
        }).isInstanceOf(ReadinessPostgresConfigurationException.class);

        assertThatThrownBy(() -> {
            ReadinessPostgresProperties properties = enabled();
            properties.setStatementTimeoutMs(0);
            properties.validate();
        }).isInstanceOf(ReadinessPostgresConfigurationException.class);

        assertThatThrownBy(() -> {
            ReadinessPostgresProperties properties = enabled();
            properties.setValidationTimeoutMs(9_000);
            properties.setConnectionTimeoutMs(1_000);
            properties.validate();
        }).isInstanceOf(ReadinessPostgresConfigurationException.class);
    }

    @Test
    void aReservedOrMalformedSchemaIsRefused() {
        for (String schema : new String[] {null, "", "Public", "public", "pg_catalog", "information_schema",
                "1st", "with-dash", "with space", "a".repeat(60)}) {
            ReadinessPostgresProperties properties = enabled();
            properties.setSchema(schema);
            assertThatThrownBy(properties::validate)
                    .as("schema %s", schema)
                    .isInstanceOf(ReadinessPostgresConfigurationException.class);
        }
        ReadinessPostgresProperties accepted = enabled();
        accepted.setSchema("campaign_readiness_t26_it");
        assertThatCode(accepted::validate).doesNotThrowAnyException();
    }

    @Test
    void diagnosticsNeverPrintTheCredentialOrTheUrl() {
        ReadinessPostgresProperties properties = enabled();
        properties.setSchema("campaign_readiness");

        assertThat(properties.toString())
                .contains("url=<redacted>")
                .contains("username=<redacted>")
                .contains("password=<redacted>")
                .doesNotContain(URL)
                .doesNotContain(PASSWORD);
    }

    @Test
    void theFlywayLogRedactsTheUrlAnyPasswordPairAndEveryRegisteredSecret() {
        ReadinessFlywayLog.registerSecret(PASSWORD);
        ReadinessFlywayLog.registerSecret(URL);

        String redacted = ReadinessFlywayLog.redact(
                "Unable to obtain connection from database (jdbc:postgresql://db.internal.example:5432/campaign"
                        + "?user=readiness-user&password=super-secret-value) for user 'readiness-user'\n"
                        + "caused by: super-secret-value");

        assertThat(redacted).doesNotContain("jdbc:postgresql").doesNotContain(PASSWORD).doesNotContain(URL);
        assertThat(redacted).doesNotContain("\n");
        assertThat(redacted).contains(ReadinessFlywayLog.REDACTED);
    }

    @Test
    void theFlywayLogBoundsAnyLineLength() {
        String redacted = ReadinessFlywayLog.redact("x".repeat(5_000));

        assertThat(redacted).hasSize(ReadinessFlywayLog.MAX_MESSAGE_LENGTH + 3).endsWith("...");
    }

    @Test
    void theFlywayLoggerUsesTheFlyway11CreatorSpi() {
        assertThat(ReadinessFlywayLogCreator.class).isAssignableTo(LogCreator.class);
        assertThat(new ReadinessFlywayLogCreator().createLogger(ReadinessPostgresConfiguration.class))
                .isInstanceOf(ReadinessFlywayLog.class);
    }

    @Test
    void everyFlywayStatementComesFromTheCommittedAssetsAndNotFromJava() throws Exception {
        // The configuration points Flyway at the committed classpath assets and at nothing else, and no Java
        // source in this package contains migration DDL.
        assertThat(ReadinessPostgresProperties.MIGRATION_LOCATIONS).isEqualTo("classpath:db/migration");

        // The scan is scoped to the opt-in PostgreSQL package: the T25 MigrationCatalog legitimately names
        // guarded statements as contract vocabulary, and it applies no DDL itself.
        Pattern ddl = Pattern.compile("(?i)\\b(CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE)\\b");
        try (var paths = java.nio.file.Files.walk(
                java.nio.file.Path.of("src/main/java/com/company/campaign/readiness/postgres"))) {
            for (java.nio.file.Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ddl.matcher(java.nio.file.Files.readString(source));
                assertThat(matcher.find())
                        .as("migration DDL must not be duplicated in %s", source.getFileName())
                        .isFalse();
            }
        }
    }

    private static ReadinessPostgresProperties enabledWith(String url, String username, String password) {
        ReadinessPostgresProperties properties = new ReadinessPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl(url);
        properties.setUsername(username);
        properties.setPassword(password);
        return properties;
    }
}
