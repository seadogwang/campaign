package com.company.campaign.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.company.campaign.readiness.postgres.ReadinessFlywayLogCreator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.BaseTypeHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.type.JdbcType;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The focused opt-in configuration that turns the committed unified business migration assets into a real
 * PostgreSQL runtime with MyBatis-Plus persistence.
 *
 * <p>It is inert unless the host sets {@code campaign.datasource.enabled=true}. When it is enabled it does
 * exactly five things, all fail-closed:
 *
 * <ol>
 *   <li>validates the host configuration before anything connects;</li>
 *   <li>builds one bounded, eagerly-connected pool in the dedicated business schema;</li>
 *   <li>runs the committed business assets with real Flyway, in deterministic order, into that schema —
 *       the migration SQL is never duplicated or rewritten in Java, and it covers every business slice;</li>
 *   <li>publishes a MyBatis-Plus {@link SqlSessionFactory} over the same pool, with the mapper XML locations
 *       and type aliases configured;</li>
 *   <li>publishes a {@link DataSourceTransactionManager} marked {@code @Primary} so that Spring Boot and
 *       MyBatis-Plus use it as the default transaction manager for the business datasource.</li>
 * </ol>
 *
 * <p>Every bean carries an explicit name and qualifier, so enabling readiness storage, durable conversation
 * persistence, production membership, production audit and the unified business datasource together creates no
 * ambiguous {@code DataSource}, {@code SqlSessionFactory}, {@code Flyway} or
 * {@code FlywayMigrationInitializer} injection point.
 *
 * <p>A missing setting, an unreachable server or a failed migration aborts the application start with a bounded
 * code and a message that carries no credential and no JDBC URL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CampaignDatasourceProperties.class)
@ConditionalOnProperty(prefix = "campaign.datasource", name = "enabled", havingValue = "true")
public class CampaignDatasourceConfiguration {

    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";

    /** The fixed pool name; it is a constant so no host value can reach a log line through it. */
    private static final String POOL_NAME = "campaign-business";

    /** The mapper XML location pattern resolved against the classpath. */
    private static final String MAPPER_LOCATIONS = "classpath*:mapper/**/*.xml";

    /** The type aliases package for MyBatis entity classes. */
    private static final String TYPE_ALIASES_PACKAGE = "com.company.campaign.persistence.entity";

    private final CampaignDatasourceProperties properties;

    public CampaignDatasourceConfiguration(CampaignDatasourceProperties properties) {
        this.properties = properties;
    }

    /**
     * Early validation hook: validates the unified business datasource properties before any bean is constructed.
     *
     * <p>A missing or out-of-bounds setting fails the application context refresh here, so no pool, no Flyway
     * runtime and no SQL session factory is ever built from invalid configuration.
     */
    @PostConstruct
    void validateConfiguration() {
        properties.validate();
    }

    /**
     * The one bounded pool of the unified business datasource.
     *
     * <p>The credential and the URL are registered for redaction before any pool or migration log can run, and
     * an unreachable or malformed configuration aborts the start with one bounded code.
     */
    @Bean(name = CampaignDatasourceProperties.DATASOURCE_BEAN_NAME, destroyMethod = "close")
    @Qualifier(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME)
    public HikariDataSource campaignBusinessDataSource() {
        properties.validate();
        ReadinessFlywayLog.registerSecret(properties.getPassword());
        ReadinessFlywayLog.registerSecret(properties.getUrl());
        try {
            return buildDataSource(properties);
        } catch (RuntimeException failure) {
            throw new CampaignDatasourceConfigurationException(
                    CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE,
                    "The configured unified business datasource pool could not be established.", failure);
        }
    }

    /**
     * The one MyBatis-Plus SQL session factory over the business pool.
     *
     * <p>It depends on the Flyway initializer to ensure the ordered migration run completes before any mapper
     * can address a table, and it configures PostgreSQL-specific settings including underscore-to-camel-case
     * mapping and PGobject type handling.
     */
    @Bean(name = CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
    @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
    public SqlSessionFactory campaignBusinessSqlSessionFactory(
            @Qualifier(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME) HikariDataSource campaignBusinessDataSource,
            @Qualifier("campaignBusinessFlywayInitializer") FlywayMigrationInitializer campaignBusinessFlywayInitializer)
            throws Exception {
        properties.validate();
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(campaignBusinessDataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS));
        factoryBean.setTypeAliasesPackage(TYPE_ALIASES_PACKAGE);
        factoryBean.setConfiguration(mybatisConfiguration());
        return factoryBean.getObject();
    }

    /**
     * The one primary transaction manager over the business datasource.
     *
     * <p>It is marked {@link Primary @Primary} so that Spring Boot and MyBatis-Plus use it as the default
     * transaction manager when the unified business datasource is enabled. Every other slice's transaction
     * manager must be explicitly qualified.
     */
    @Bean(name = CampaignDatasourceProperties.TRANSACTION_MANAGER_BEAN_NAME)
    @Qualifier(CampaignDatasourceProperties.TRANSACTION_MANAGER_BEAN_NAME)
    @Primary
    public DataSourceTransactionManager campaignBusinessTransactionManager(
            @Qualifier(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME) HikariDataSource campaignBusinessDataSource) {
        return new DataSourceTransactionManager(campaignBusinessDataSource);
    }

    /**
     * The real Flyway runtime over the committed unified business asset set.
     *
     * <p>The location is the one committed unified business migration set and nothing else, the schema is the
     * dedicated boundary from configuration, and cleaning is disabled so this configuration can never destroy
     * data. The unified run records its history in a dedicated table rather than the default
     * {@code flyway_schema_history}, so it never validates against a legacy readiness, conversation, membership
     * or audit history a host may already carry in the same schema; such a history therefore needs no repair and
     * no clean. The bounded logger is the only log surface, so a Flyway line never carries the URL.
     */
    @Bean(name = "campaignBusinessFlyway")
    @Qualifier("campaignBusinessFlyway")
    public Flyway campaignBusinessFlyway(
            @Qualifier(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME) HikariDataSource campaignBusinessDataSource) {
        properties.validate();
        return Flyway.configure()
                .dataSource(campaignBusinessDataSource)
                .schemas(properties.getSchema())
                .defaultSchema(properties.getSchema())
                .createSchemas(true)
                .locations(CampaignDatasourceProperties.MIGRATION_LOCATIONS.split(","))
                .table(CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .loggers(ReadinessFlywayLogCreator.class.getName())
                .load();
    }

    /** Applies the ordered unified business migration set at startup; a failure fails the start. */
    @Bean(name = "campaignBusinessFlywayInitializer")
    @Qualifier("campaignBusinessFlywayInitializer")
    public FlywayMigrationInitializer campaignBusinessFlywayInitializer(
            @Qualifier("campaignBusinessFlyway") Flyway campaignBusinessFlyway) {
        return new FlywayMigrationInitializer(campaignBusinessFlyway, null);
    }

    /**
     * The static MapperScannerConfigurer that scans the mapper package.
     *
     * <p>It is a static bean method so it processes early, before the regular bean initialization, ensuring
     * that the mapper interfaces are registered against the business SqlSessionFactory without any ordering
     * ambiguity. The base package and the SqlSessionFactory bean name are both committed constants.
     */
    @Bean
    public static MapperScannerConfigurer campaignBusinessMapperScannerConfigurer() {
        MapperScannerConfigurer scanner = new MapperScannerConfigurer();
        scanner.setBasePackage(CampaignDatasourceProperties.MAPPER_SCAN_BASE_PACKAGE);
        scanner.setSqlSessionFactoryBeanName(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME);
        return scanner;
    }

    /**
     * Builds the MyBatis-Plus configuration with PostgreSQL-specific settings.
     *
     * <p>Every connection to the business schema runs with underscore-to-camel-case mapping enabled, so a
     * database column named {@code created_at} maps to the Java property {@code createdAt} without any
     * explicit {@code @TableField} annotation. A PGobject type handler is registered so PostgreSQL
     * {@code UNKNOWN}-type columns can be read and written through the standard JDBC driver.
     */
    private static MybatisConfiguration mybatisConfiguration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(PGobject.class, new PgObjectTypeHandler());
        return configuration;
    }

    /**
     * Builds the bounded pool for one validated properties object.
     *
     * <p>Every resource limit is set from validated configuration rather than from a library default: the pool
     * size, the connection timeout, the validation timeout and the server-side {@code statement_timeout},
     * {@code lock_timeout} and {@code idle_in_transaction_session_timeout} are all bounded. The pool is created
     * eagerly, so an unreachable or misconfigured server fails the application start rather than surfacing
     * later as a runtime surprise.
     *
     * <p>The JDBC URL and the credential are never logged and never appear in a thrown message; the driver's
     * {@code options} parameter carries the server-side timeouts so no session statement can run unbounded and
     * no staged transaction can sit idle forever, and the dedicated schema is bound as the session
     * {@code search_path}.
     */
    private static HikariDataSource buildDataSource(CampaignDatasourceProperties properties) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(POOL_NAME);
        configuration.setDriverClassName(POSTGRESQL_DRIVER);
        configuration.setJdbcUrl(properties.getUrl());
        configuration.setUsername(properties.getUsername());
        configuration.setPassword(properties.getPassword());
        configuration.setMaximumPoolSize(properties.getMaximumPoolSize());
        configuration.setMinimumIdle(properties.getMinimumIdle());
        configuration.setConnectionTimeout(properties.getConnectionTimeoutMs());
        configuration.setValidationTimeout(properties.getValidationTimeoutMs());
        configuration.setInitializationFailTimeout(properties.getConnectionTimeoutMs());
        configuration.setRegisterMbeans(false);
        configuration.setConnectionInitSql(searchPath(properties.getSchema()));
        configuration.addDataSourceProperty("options", serverSideTimeouts(properties));
        configuration.addDataSourceProperty("ApplicationName", POOL_NAME);
        return new HikariDataSource(configuration);
    }

    /**
     * Binds every connection to the dedicated business schema.
     *
     * <p>The schema is already validated against {@link CampaignDatasourceProperties#SCHEMA_PATTERN}, so it is
     * a bounded identifier and cannot carry a statement.
     */
    private static String searchPath(String schema) {
        return "SET search_path TO \"" + schema + "\", pg_catalog";
    }

    /**
     * The server-side statement, lock and idle-in-transaction timeouts, so no business statement can run
     * unbounded and no staged audit transaction can sit idle forever.
     */
    private static String serverSideTimeouts(CampaignDatasourceProperties properties) {
        return "-c statement_timeout=" + properties.getStatementTimeoutMs()
                + " -c lock_timeout=" + properties.getLockTimeoutMs()
                + " -c idle_in_transaction_session_timeout=" + properties.getIdleTransactionTimeoutMs();
    }

    /**
     * A bounded MyBatis type handler for PostgreSQL {@link PGobject} values.
     *
     * <p>PostgreSQL {@code UNKNOWN}-type columns are delivered as {@code PGobject} through the JDBC driver.
     * This handler reads the string value and wraps it back as a {@code PGobject} so MyBatis-Plus can
     * marshal the value without custom per-column type declarations. Null values are passed through; a null
     * column produces a null parameter and a null result produces a null Java reference.
     */
    private static final class PgObjectTypeHandler extends BaseTypeHandler<PGobject> {

        @Override
        public void setNonNullParameter(PreparedStatement ps, int i, PGobject parameter, JdbcType jdbcType)
                throws SQLException {
            ps.setObject(i, parameter);
        }

        @Override
        public PGobject getNullableResult(ResultSet rs, String columnName) throws SQLException {
            Object value = rs.getObject(columnName);
            return toPgObject(value);
        }

        @Override
        public PGobject getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
            Object value = rs.getObject(columnIndex);
            return toPgObject(value);
        }

        @Override
        public PGobject getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
            Object value = cs.getObject(columnIndex);
            return toPgObject(value);
        }

        private static PGobject toPgObject(Object value) throws SQLException {
            if (value == null) {
                return null;
            }
            if (value instanceof PGobject pgObject) {
                return pgObject;
            }
            PGobject result = new PGobject();
            result.setType("unknown");
            result.setValue(value.toString());
            return result;
        }
    }
}
