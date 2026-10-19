package com.company.campaign.conversation.postgres;

import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.company.campaign.readiness.postgres.ReadinessFlywayLogCreator;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The real-PostgreSQL settings the environment-gated durable-conversation verifier reads.
 *
 * <p>Everything comes from the environment: no credential, URL or database name is committed. The verifier is
 * skipped entirely when {@code CAMPAIGN_CONVERSATION_POSTGRES_URL} is absent, and an absent or malformed setting
 * is refused rather than defaulted, so a run either proves the boundary or reports an explicit blocker.
 *
 * <p>The dedicated schema rule is what keeps a run from touching anything but its own boundary: a reserved
 * schema is refused by the shared configuration, cleaning is only permitted on a schema plainly named for this
 * verifier (or when the operator explicitly allows it), and a production environment is never cleaned.
 */
final class ConversationPostgresTestEnvironment {

    static final String URL_VARIABLE = "CAMPAIGN_CONVERSATION_POSTGRES_URL";
    static final String USERNAME_VARIABLE = "CAMPAIGN_CONVERSATION_POSTGRES_USERNAME";
    static final String PASSWORD_VARIABLE = "CAMPAIGN_CONVERSATION_POSTGRES_PASSWORD";
    static final String SCHEMA_VARIABLE = "CAMPAIGN_CONVERSATION_POSTGRES_SCHEMA";
    static final String ALLOW_CLEAN_VARIABLE = "CAMPAIGN_CONVERSATION_POSTGRES_ALLOW_CLEAN";

    static final String DEFAULT_SCHEMA = "campaign_conversation_t34b_it";

    private static final Pattern DEDICATED_SCHEMA = Pattern.compile("^campaign_conversation(_[a-z0-9_]+)?$");

    private ConversationPostgresTestEnvironment() {
    }

    /** True when the environment supplies a connection target. */
    static boolean isConfigured() {
        return text(URL_VARIABLE) != null;
    }

    /** The dedicated schema this run uses. */
    static String schema() {
        String value = text(SCHEMA_VARIABLE);
        return value == null ? DEFAULT_SCHEMA : value;
    }

    /** The validated properties for the configured environment. */
    static ConversationPostgresProperties properties() {
        ConversationPostgresProperties properties = new ConversationPostgresProperties();
        properties.setMode(ConversationPostgresProperties.Mode.POSTGRESQL);
        properties.setUrl(text(URL_VARIABLE));
        properties.setUsername(text(USERNAME_VARIABLE));
        properties.setPassword(text(PASSWORD_VARIABLE));
        properties.setSchema(schema());
        properties.validate();
        return properties;
    }

    /** A bounded pool for the configured environment. */
    static HikariDataSource dataSource() {
        ConversationPostgresProperties properties = properties();
        ReadinessFlywayLog.registerSecret(properties.getPassword());
        ReadinessFlywayLog.registerSecret(properties.getUrl());
        return MyBatisTestSupport.dataSource(
                "campaign-conversation-it", properties.getUrl(), properties.getUsername(),
                properties.getPassword(), properties.getSchema());
    }

    /** Whether this run may clean its boundary. Only this verifier's own schema, or an explicit opt-in, may. */
    static boolean mayClean() {
        if ("true".equalsIgnoreCase(text(ALLOW_CLEAN_VARIABLE))) {
            return true;
        }
        return DEDICATED_SCHEMA.matcher(schema()).matches();
    }

    /** The real Flyway runtime for the committed Campaign conversation asset set, over the given boundary. */
    static Flyway flyway(DataSource dataSource, boolean cleanAllowed) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema())
                .defaultSchema(schema())
                .createSchemas(true)
                .locations(ConversationPostgresProperties.MIGRATION_LOCATIONS)
                .failOnMissingLocations(true)
                .cleanDisabled(!cleanAllowed)
                .loggers(ReadinessFlywayLogCreator.class.getName())
                .load();
    }

    /** The applied migration versions, in order. */
    static List<Integer> appliedVersions(Flyway flyway) {
        List<Integer> versions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null) {
                versions.add(Integer.parseInt(info.getVersion().getVersion()));
            }
        }
        return List.copyOf(versions);
    }

    /** The bounded server identity of the engine that actually answered. */
    static String serverVersion(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT version()");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return "unknown";
            }
            String version = resultSet.getString(1);
            return version == null ? "unknown" : version.substring(0, Math.min(64, version.length()));
        } catch (SQLException exception) {
            throw new IllegalStateException("The PostgreSQL version could not be read", exception);
        }
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}
