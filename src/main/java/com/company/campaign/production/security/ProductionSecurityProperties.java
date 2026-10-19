package com.company.campaign.production.security;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the default-off production identity boundary.
 *
 * <p>Nothing here has a default that could accidentally enable production identity: {@link #isEnabled()} is
 * {@code false} unless the host sets {@code campaign.production.enabled=true}. The issuer, the JWK Set URI, the
 * audience, the connection string and the credential have no committed value at all and are supplied by the host
 * through explicit configuration or the equivalent relaxed-binding environment variables.
 *
 * <p>Every bound is validated before a decoder, a pool or a repository is created. {@link #toString()} redacts
 * the issuer, the JWK Set URI, the audience, the selector claim, the credential and the JDBC URL so a stray log
 * line or framework diagnostic cannot disclose any of them.
 */
@ConfigurationProperties(prefix = "campaign.production")
public class ProductionSecurityProperties {

    /** The largest accepted subject or issuer claim length. */
    public static final int MAX_IDENTITY_CLAIM_LENGTH = 255;

    /** The largest accepted opaque membership-selector claim length. */
    public static final int MAX_SELECTOR_CLAIM_LENGTH = 255;

    /** The asymmetric algorithms an allowlist may name; symmetric and unsecured signatures are never allowed. */
    public static final Set<String> ASYMMETRIC_ALGORITHMS = Set.of(
            "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512");

    /** The schema pattern a dedicated production membership boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable production membership boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    private boolean enabled = false;
    private final Jwt jwt = new Jwt();
    private final Membership membership = new Membership();

    /** True only when the host explicitly opted into the production identity boundary. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Membership getMembership() {
        return membership;
    }

    /** Uses the canonical datasource when PostgreSQL membership has no legacy connection target. */
    public void inheritDatasourceFrom(CampaignDatasourceProperties datasource) {
        if (!enabled || membership.mode != ProductionMembershipMode.POSTGRESQL
                || !datasource.isEnabled() || (membership.url != null && !membership.url.isBlank())) {
            return;
        }
        membership.url = datasource.getUrl();
        membership.username = datasource.getUsername();
        membership.password = datasource.getPassword();
        membership.schema = datasource.getSchema();
        membership.maximumPoolSize = datasource.getMaximumPoolSize();
        membership.minimumIdle = datasource.getMinimumIdle();
        membership.connectionTimeoutMs = datasource.getConnectionTimeoutMs();
        membership.validationTimeoutMs = datasource.getValidationTimeoutMs();
        membership.statementTimeoutMs = datasource.getStatementTimeoutMs();
        membership.lockTimeoutMs = datasource.getLockTimeoutMs();
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the production boundary off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        jwt.validate();
        membership.validate();
    }

    /** The JWT verification settings of the production boundary. */
    public static class Jwt {

        private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
        private static final Duration MIN_ISSUED_AT_AGE = Duration.ofSeconds(60);
        private static final Duration MAX_ISSUED_AT_AGE = Duration.ofHours(24);

        private String issuerUri;
        private String jwkSetUri;
        private String audience;
        private Set<String> allowedAlgorithms = new LinkedHashSet<>(Set.of("RS256", "ES256"));
        private Duration clockSkew = Duration.ofSeconds(60);
        private Duration maxIssuedAtAge = Duration.ofHours(1);

        /** The exact issuer a verified token must carry. */
        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        /** The configured JWK Set URI; required unless a host supplies its own decoder. */
        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        /** The exact audience a verified token must contain. */
        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        /** The allowlisted asymmetric signature algorithms. */
        public Set<String> getAllowedAlgorithms() {
            return allowedAlgorithms;
        }

        public void setAllowedAlgorithms(Set<String> allowedAlgorithms) {
            this.allowedAlgorithms = allowedAlgorithms;
        }

        /** The bounded clock skew applied to expiration, not-before and issued-at. */
        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        /** The largest age an issued-at claim may have. */
        public Duration getMaxIssuedAtAge() {
            return maxIssuedAtAge;
        }

        public void setMaxIssuedAtAge(Duration maxIssuedAtAge) {
            this.maxIssuedAtAge = maxIssuedAtAge;
        }

        private void validate() {
            if (issuerUri == null || issuerUri.isBlank() || issuerUri.length() > 512) {
                throw invalid("jwt.issuerUri");
            }
            if (audience == null || audience.isBlank() || audience.length() > MAX_IDENTITY_CLAIM_LENGTH) {
                throw invalid("jwt.audience");
            }
            if (allowedAlgorithms == null || allowedAlgorithms.isEmpty()
                    || allowedAlgorithms.size() > ASYMMETRIC_ALGORITHMS.size()
                    || !ASYMMETRIC_ALGORITHMS.containsAll(allowedAlgorithms)) {
                throw invalid("jwt.allowedAlgorithms");
            }
            if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
                throw invalid("jwt.clockSkew");
            }
            if (maxIssuedAtAge == null || maxIssuedAtAge.compareTo(MIN_ISSUED_AT_AGE) < 0
                    || maxIssuedAtAge.compareTo(MAX_ISSUED_AT_AGE) > 0) {
                throw invalid("jwt.maxIssuedAtAge");
            }
        }

        private static ProductionSecurityConfigurationException invalid(String field) {
            return new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_SECURITY_CONFIGURATION_INVALID,
                    "The opt-in production identity configuration is missing or out of bounds: " + field);
        }

        /** Redacted diagnostics: neither the issuer, the JWK Set URI nor the audience is printed. */
        @Override
        public String toString() {
            return "Jwt[issuer=<redacted>"
                    + ", jwkSetUri=<redacted>"
                    + ", audience=<redacted>"
                    + ", allowedAlgorithms=" + allowedAlgorithms
                    + ", clockSkew=" + clockSkew
                    + ", maxIssuedAtAge=" + maxIssuedAtAge + ']';
        }
    }

    /** The server-side membership resolution settings of the production boundary. */
    public static class Membership {

        private static final int MAXIMUM_POOL_SIZE = 16;
        private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;
        private static final int MAXIMUM_MEMBERSHIP_ROWS = 8;
        private static final int MAXIMUM_PERMISSION_ROWS = 64;

        private ProductionMembershipMode mode = ProductionMembershipMode.POSTGRESQL;
        private String url;
        private String username;
        private String password;
        private String schema = "campaign_production";
        private String selectClaim;
        private int maximumPoolSize = 4;
        private int minimumIdle = 0;
        private int connectionTimeoutMs = 5_000;
        private int validationTimeoutMs = 3_000;
        private int statementTimeoutMs = 5_000;
        private int lockTimeoutMs = 2_000;
        private int queryTimeoutMs = 3_000;
        private int maxMembershipRows = 2;
        private int maxPermissionRows = 16;

        public ProductionMembershipMode getMode() {
            return mode;
        }

        public void setMode(ProductionMembershipMode mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        /** The dedicated schema the production membership migration set and the store share. */
        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        /**
         * The optional JWT claim whose value is an opaque membership selector.
         *
         * <p>Only the claim <em>name</em> is host configuration; the value is treated as opaque and never as a
         * tenant, program, user, DataScope, permission or any other trusted fact.
         */
        public String getSelectClaim() {
            return selectClaim;
        }

        public void setSelectClaim(String selectClaim) {
            this.selectClaim = selectClaim;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public int getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(int connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }

        public int getValidationTimeoutMs() {
            return validationTimeoutMs;
        }

        public void setValidationTimeoutMs(int validationTimeoutMs) {
            this.validationTimeoutMs = validationTimeoutMs;
        }

        public int getStatementTimeoutMs() {
            return statementTimeoutMs;
        }

        public void setStatementTimeoutMs(int statementTimeoutMs) {
            this.statementTimeoutMs = statementTimeoutMs;
        }

        public int getLockTimeoutMs() {
            return lockTimeoutMs;
        }

        public void setLockTimeoutMs(int lockTimeoutMs) {
            this.lockTimeoutMs = lockTimeoutMs;
        }

        /** The JDBC statement query timeout, in milliseconds. */
        public int getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        public void setQueryTimeoutMs(int queryTimeoutMs) {
            this.queryTimeoutMs = queryTimeoutMs;
        }

        /** The maximum membership rows one lookup may return before it is refused as ambiguous. */
        public int getMaxMembershipRows() {
            return maxMembershipRows;
        }

        public void setMaxMembershipRows(int maxMembershipRows) {
            this.maxMembershipRows = maxMembershipRows;
        }

        /** The maximum permission rows one membership may carry before it is refused. */
        public int getMaxPermissionRows() {
            return maxPermissionRows;
        }

        public void setMaxPermissionRows(int maxPermissionRows) {
            this.maxPermissionRows = maxPermissionRows;
        }

        private void validate() {
            if (mode == null) {
                throw invalid("membership.mode");
            }
            if (mode == ProductionMembershipMode.HOST) {
                return;
            }
            requireText(url, "membership.url");
            requireText(username, "membership.username");
            requireText(password, "membership.password");
            requireSchema();
            if (selectClaim != null && (selectClaim.isBlank() || selectClaim.length() > MAX_IDENTITY_CLAIM_LENGTH)) {
                throw invalid("membership.selectClaim");
            }
            requireRange(maximumPoolSize, 1, MAXIMUM_POOL_SIZE, "membership.maximumPoolSize");
            requireRange(minimumIdle, 0, maximumPoolSize, "membership.minimumIdle");
            requireRange(connectionTimeoutMs, 250, 30_000, "membership.connectionTimeoutMs");
            requireRange(validationTimeoutMs, 250, 10_000, "membership.validationTimeoutMs");
            if (validationTimeoutMs > connectionTimeoutMs) {
                throw invalid("membership.validationTimeoutMs");
            }
            requireRange(statementTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "membership.statementTimeoutMs");
            requireRange(lockTimeoutMs, 100, 10_000, "membership.lockTimeoutMs");
            requireRange(queryTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "membership.queryTimeoutMs");
            requireRange(maxMembershipRows, 2, MAXIMUM_MEMBERSHIP_ROWS, "membership.maxMembershipRows");
            requireRange(maxPermissionRows, 1, MAXIMUM_PERMISSION_ROWS, "membership.maxPermissionRows");
        }

        private void requireSchema() {
            if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches() || RESERVED_SCHEMAS.contains(schema)) {
                throw invalid("membership.schema");
            }
        }

        private void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw invalid(field);
            }
        }

        private void requireRange(int value, int minimum, int maximum, String field) {
            if (value < minimum || value > maximum) {
                throw invalid(field);
            }
        }

        private static ProductionSecurityConfigurationException invalid(String field) {
            return new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_SECURITY_CONFIGURATION_INVALID,
                    "The opt-in production identity configuration is missing or out of bounds: " + field);
        }

        /** Redacted diagnostics: neither the credential, the JDBC URL nor the selector claim is printed. */
        @Override
        public String toString() {
            return "Membership[mode=" + mode
                    + ", url=<redacted>"
                    + ", username=<redacted>"
                    + ", password=<redacted>"
                    + ", schema=" + schema
                    + ", selectClaim=<redacted>"
                    + ", maximumPoolSize=" + maximumPoolSize
                    + ", connectionTimeoutMs=" + connectionTimeoutMs
                    + ", queryTimeoutMs=" + queryTimeoutMs
                    + ", maxMembershipRows=" + maxMembershipRows
                    + ", maxPermissionRows=" + maxPermissionRows + ']';
        }
    }

    /** Redacted diagnostics: no issuer, JWK Set URI, audience or credential is printed. */
    @Override
    public String toString() {
        return "ProductionSecurityProperties[enabled=" + enabled + ", jwt=" + jwt + ", membership=" + membership + ']';
    }
}
