package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The host-supplied, default-off configuration of the production Provider adapters.
 *
 * <p>Nothing here has a default that could accidentally reach a Provider: both adapters start disabled, and the
 * endpoint, the host allowlist, the fixed provider id, the fixed owner tenant id and the credential have no
 * committed value at all. Every value comes from explicit host configuration or the equivalent relaxed-binding
 * environment variables, and the production boundary itself stays inert unless {@code campaign.production.enabled}
 * is also true.
 *
 * <p>Every bound is validated before any client is built. An enabled adapter requires an explicit HTTPS base
 * endpoint whose host the explicit allowlist names, a fixed provider id, a fixed owner tenant id, a credential,
 * bounded connect and request timeouts, a bounded response size, the exact contract version and an explicit
 * assertion of business-key idempotency and reliable query-by-key. A plaintext endpoint is representable only for
 * an explicit loopback host with {@code require-https=false}; such a configuration can never produce production
 * evidence and its capability can never declare production.
 *
 * <p>{@link #toString()} redacts the endpoint, the credential, the allowlist, the provider id and the owner;
 * {@link #configurationHash(ProductionProviderKind)} is a one-way canonical hash published as evidence that
 * discloses none of them.
 */
@ConfigurationProperties(prefix = "campaign.production.provider")
public class ProductionProviderProperties {

    /** The one exact wire contract version this build speaks; every adapter must restate it. */
    public static final String CONTRACT_VERSION = "campaign-production-provider/1";

    /** The one route prefix every adapter uses under the configured base endpoint. */
    public static final String ROUTE_PREFIX = "/provider/v1/";

    /** The one route suffix of the submission and reconciliation resource. */
    public static final String ROUTE_SUFFIX = "/effects";

    /** The largest accepted allowlist size. */
    public static final int MAX_ALLOWED_HOSTS = 8;

    /** The largest accepted response byte limit. */
    public static final int MAX_RESPONSE_BYTES_LIMIT = 1_048_576;

    /** The largest accepted bounded query attempt count. */
    public static final int MAX_QUERY_ATTEMPTS_LIMIT = 5;

    /** The largest accepted provider id or owner tenant id length. */
    public static final int MAX_IDENTITY_LENGTH = 128;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The literal loopback hosts for which a plaintext endpoint is representable, and never a real endpoint. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "localhost");

    private final Adapter message = new Adapter();

    private final Adapter reward = new Adapter();

    private int queryMaxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(250);
    private Duration maxBackoff = Duration.ofSeconds(2);
    private Duration evidenceMaxAge = Duration.ofDays(7);
    private List<String> gatePins = new ArrayList<>();

    /** The message adapter's settings. */
    public Adapter getMessage() {
        return message;
    }

    /** The reward adapter's settings. */
    public Adapter getReward() {
        return reward;
    }

    /** The one adapter's settings for the given kind. */
    public Adapter adapter(ProductionProviderKind kind) {
        return kind == ProductionProviderKind.MESSAGE ? message : reward;
    }

    /** The bounded number of reconciliation query attempts one call may make. */
    public int getQueryMaxAttempts() {
        return queryMaxAttempts;
    }

    public void setQueryMaxAttempts(int queryMaxAttempts) {
        this.queryMaxAttempts = queryMaxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    /** The bounded age a live Provider verification may have before it stops being current evidence. */
    public Duration getEvidenceMaxAge() {
        return evidenceMaxAge;
    }

    public void setEvidenceMaxAge(Duration evidenceMaxAge) {
        this.evidenceMaxAge = evidenceMaxAge;
    }

    /** The exact Gate pins this configuration is asserted against; published as evidence. */
    public List<String> getGatePins() {
        return gatePins;
    }

    public void setGatePins(List<String> gatePins) {
        this.gatePins = gatePins == null ? new ArrayList<>() : gatePins;
    }

    /** True when at least one adapter is explicitly enabled. */
    public boolean anyEnabled() {
        return message.isEnabled() || reward.isEnabled();
    }

    /** True when the exact given adapter is explicitly enabled. */
    public boolean enabled(ProductionProviderKind kind) {
        return adapter(kind).isEnabled();
    }

    /**
     * Validates the whole configuration, refusing an enabled adapter with a missing or out-of-bounds setting.
     *
     * <p>A fully disabled configuration is always valid and validates only the shared bounds, so the normal
     * application never fails because a host left both production Providers off.
     */
    public void validate() {
        message.validate("message");
        reward.validate("reward");
        requireRange(queryMaxAttempts, 1, MAX_QUERY_ATTEMPTS_LIMIT, "queryMaxAttempts");
        requireDuration(initialBackoff, 1, 5_000, "initialBackoff");
        requireDuration(maxBackoff, 1, 30_000, "maxBackoff");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw invalid("maxBackoff");
        }
        requireDuration(evidenceMaxAge, 60_000, 30L * 24 * 60 * 60 * 1_000, "evidenceMaxAge");
        if (gatePins.size() > 32) {
            throw invalid("gatePins");
        }
        for (String pin : gatePins) {
            if (pin == null || pin.isBlank() || pin.length() > 256) {
                throw invalid("gatePins");
            }
        }
    }

    /**
     * The one-way canonical hash of the whole effective configuration of one adapter, safe to publish as
     * provider evidence.
     *
     * <p>It is a hash, so it discloses none of the endpoint, the allowlist, the provider id, the owner or the
     * credential, yet it pins the exact configuration a verification was performed under: rotating the
     * credential, moving the endpoint or changing a bound changes the hash.
     */
    public ContentHash configurationHash(ProductionProviderKind kind) {
        Adapter adapter = adapter(kind);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("providerKind", kind.providerKind());
        material.put("contractVersion", CONTRACT_VERSION);
        material.put("endpoint", adapter.getEndpoint() == null ? "" : adapter.getEndpoint());
        material.put("allowedHosts", List.copyOf(adapter.getAllowedHosts()));
        material.put("providerId", adapter.getProviderId() == null ? "" : adapter.getProviderId());
        material.put("ownerTenantId", adapter.getOwnerTenantId() == null ? "" : adapter.getOwnerTenantId());
        material.put("credential", adapter.getCredential() == null ? "" : adapter.getCredential());
        material.put("apiVersion", adapter.getApiVersion() == null ? "" : adapter.getApiVersion());
        material.put("requireHttps", adapter.isRequireHttps());
        material.put("assertBusinessKeyIdempotency", adapter.isAssertBusinessKeyIdempotency());
        material.put("assertReliableQueryByKey", adapter.isAssertReliableQueryByKey());
        material.put("connectTimeoutMs", adapter.getConnectTimeout() == null
                ? -1 : adapter.getConnectTimeout().toMillis());
        material.put("requestTimeoutMs", adapter.getRequestTimeout() == null
                ? -1 : adapter.getRequestTimeout().toMillis());
        material.put("maxResponseBytes", adapter.getMaxResponseBytes());
        material.put("queryMaxAttempts", queryMaxAttempts);
        material.put("initialBackoffMs", initialBackoff == null ? -1 : initialBackoff.toMillis());
        material.put("maxBackoffMs", maxBackoff == null ? -1 : maxBackoff.toMillis());
        return CANONICAL_JSON.hash(material);
    }

    /** The one-way fingerprint of one adapter's fixed provider id, safe to publish as evidence. */
    public ContentHash providerIdFingerprint(ProductionProviderKind kind) {
        String providerId = adapter(kind).getProviderId();
        if (providerId == null) {
            return ContentHash.sha256("production-provider-unconfigured".getBytes(StandardCharsets.UTF_8));
        }
        return ContentHash.sha256(providerId.getBytes(StandardCharsets.UTF_8));
    }

    /** The exact capability one adapter declares, with its own fixed owner, kind and production flag. */
    public ProviderCapability capability(ProductionProviderKind kind) {
        return adapter(kind).capability(kind.actionKind());
    }

    /** The exact capability hash one adapter publishes as evidence. */
    public ContentHash capabilityHash(ProductionProviderKind kind) {
        return capability(kind).capabilityHash();
    }

    private static void requireDuration(Duration value, long minMillis, long maxMillis, String field) {
        if (value == null || value.toMillis() < minMillis || value.toMillis() > maxMillis) {
            throw invalid(field);
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw invalid(field);
        }
    }

    static ProductionProviderException invalid(String field) {
        return new ProductionProviderException(
                ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID,
                "The opt-in production Provider configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: no endpoint, credential, allowlist, provider id or owner is printed. */
    @Override
    public String toString() {
        return "ProductionProviderProperties[message=" + message
                + ", reward=" + reward
                + ", queryMaxAttempts=" + queryMaxAttempts
                + ", evidenceMaxAge=" + evidenceMaxAge
                + ", gatePins=" + gatePins.size() + ']';
    }

    /**
     * One adapter's host-supplied settings: the endpoint, the fixed identities, the credential and the bounds.
     *
     * <p>Every field has a safe default or no value at all, and {@link #validate(String)} refuses an enabled
     * adapter whose endpoint, allowlist, identities, credential, version or bounds are absent or out of range.
     */
    public static final class Adapter {

        private boolean enabled = false;
        private boolean requireHttps = true;
        private String endpoint;
        private List<String> allowedHosts = new ArrayList<>();
        private String providerId;
        private String ownerTenantId;
        private String credential;
        private String apiVersion = CONTRACT_VERSION;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxResponseBytes = 65_536;
        private boolean assertBusinessKeyIdempotency = false;
        private boolean assertReliableQueryByKey = false;

        /** True only when the host explicitly opted this adapter in. */
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Whether plaintext HTTP is refused; a production configuration must keep this true. */
        public boolean isRequireHttps() {
            return requireHttps;
        }

        public void setRequireHttps(boolean requireHttps) {
            this.requireHttps = requireHttps;
        }

        /** The configured base endpoint; never printed. */
        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        /** The explicit host allowlist the endpoint host must be named by. */
        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }

        /** The fixed provider id this adapter declares; never printed. */
        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId;
        }

        /** The fixed owner tenant id this adapter serves; never printed. */
        public String getOwnerTenantId() {
            return ownerTenantId;
        }

        public void setOwnerTenantId(String ownerTenantId) {
            this.ownerTenantId = ownerTenantId;
        }

        /** The API credential; never printed or hashed in the clear. */
        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        /** The exact API/contract version sent with every request. */
        public String getApiVersion() {
            return apiVersion;
        }

        public void setApiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        /** Whether the host explicitly asserts this Provider honours a stable business key. */
        public boolean isAssertBusinessKeyIdempotency() {
            return assertBusinessKeyIdempotency;
        }

        public void setAssertBusinessKeyIdempotency(boolean assertBusinessKeyIdempotency) {
            this.assertBusinessKeyIdempotency = assertBusinessKeyIdempotency;
        }

        /** Whether the host explicitly asserts this Provider reliably answers query-by-key. */
        public boolean isAssertReliableQueryByKey() {
            return assertReliableQueryByKey;
        }

        public void setAssertReliableQueryByKey(boolean assertReliableQueryByKey) {
            this.assertReliableQueryByKey = assertReliableQueryByKey;
        }

        /** Validates this adapter; a disabled adapter is always valid and validates nothing. */
        public void validate(String fieldPrefix) {
            if (!enabled) {
                return;
            }
            validateEndpoint(fieldPrefix);
            if (providerId == null || !providerId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw invalid(fieldPrefix + ".providerId");
            }
            if (ownerTenantId == null || !ownerTenantId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw invalid(fieldPrefix + ".ownerTenantId");
            }
            if (credential == null || credential.isBlank() || credential.length() > 4_096) {
                throw invalid(fieldPrefix + ".credential");
            }
            if (!CONTRACT_VERSION.equals(apiVersion)) {
                throw invalid(fieldPrefix + ".apiVersion");
            }
            requireDuration(connectTimeout, 250, 30_000, fieldPrefix + ".connectTimeout");
            requireDuration(requestTimeout, 1_000, 300_000, fieldPrefix + ".requestTimeout");
            requireRange(maxResponseBytes, 1_024, MAX_RESPONSE_BYTES_LIMIT, fieldPrefix + ".maxResponseBytes");
            if (!assertBusinessKeyIdempotency) {
                throw invalid(fieldPrefix + ".assertBusinessKeyIdempotency");
            }
            if (!assertReliableQueryByKey) {
                throw invalid(fieldPrefix + ".assertReliableQueryByKey");
            }
        }

        private void validateEndpoint(String fieldPrefix) {
            URI uri = parseEndpoint(fieldPrefix);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (requireHttps && !https) {
                throw invalid(fieldPrefix + ".endpoint");
            }
            if (allowedHosts.isEmpty() || allowedHosts.size() > MAX_ALLOWED_HOSTS) {
                throw invalid(fieldPrefix + ".allowedHosts");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = false;
            for (String entry : allowedHosts) {
                if (entry == null || entry.isBlank() || entry.length() > 253) {
                    throw invalid(fieldPrefix + ".allowedHosts");
                }
                if (entry.toLowerCase(Locale.ROOT).equals(host)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                throw invalid(fieldPrefix + ".allowedHosts");
            }
        }

        private URI parseEndpoint(String fieldPrefix) {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (RuntimeException failure) {
                throw invalid(fieldPrefix + ".endpoint");
            }
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
            if ((!https && !loopbackHttp) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                // HTTPS is required; a plaintext endpoint is representable only for an explicit loopback host.
                throw invalid(fieldPrefix + ".endpoint");
            }
            return uri;
        }

        /**
         * True only for a validated HTTPS configuration that explicitly asserts business-key idempotency and
         * reliable query-by-key.
         *
         * <p>A loopback plaintext configuration always answers {@code false}, so a configuration that exists
         * only to make deterministic tests possible can never declare production capability.
         */
        public boolean productionCapable() {
            if (!enabled || !assertBusinessKeyIdempotency || !assertReliableQueryByKey) {
                return false;
            }
            try {
                URI uri = URI.create(endpoint);
                return requireHttps && "https".equalsIgnoreCase(uri.getScheme());
            } catch (RuntimeException failure) {
                return false;
            }
        }

        /** The exact capability this adapter declares for one action kind. */
        public ProviderCapability capability(ActionKind actionKind) {
            boolean production = productionCapable();
            return ProviderCapability.of(
                    providerId, ownerTenantId, actionKind,
                    assertBusinessKeyIdempotency, assertReliableQueryByKey, production);
        }

        /** The one submission URI this adapter posts to; derived from the base endpoint at construction. */
        public URI submitEndpoint(ProductionProviderKind kind) {
            URI base = URI.create(endpoint);
            String path = base.getPath() == null ? "" : base.getPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            path = path + ROUTE_PREFIX + kind.pathSegment() + ROUTE_SUFFIX;
            try {
                return new URI(base.getScheme(), base.getAuthority(), path, null, null);
            } catch (Exception failure) {
                throw invalid("endpoint");
            }
        }

        /** Redacted diagnostics: neither the endpoint, the credential, the id nor the owner is printed. */
        @Override
        public String toString() {
            return "Adapter[enabled=" + enabled
                    + ", requireHttps=" + requireHttps
                    + ", endpoint=<redacted>"
                    + ", allowedHosts=" + allowedHosts.size()
                    + ", providerId=<redacted>"
                    + ", ownerTenantId=<redacted>"
                    + ", credential=<redacted>"
                    + ", apiVersion=" + apiVersion
                    + ", connectTimeout=" + connectTimeout
                    + ", requestTimeout=" + requestTimeout
                    + ", maxResponseBytes=" + maxResponseBytes
                    + ", assertsIdempotency=" + assertBusinessKeyIdempotency
                    + ", assertsReconciliation=" + assertReliableQueryByKey + ']';
        }
    }
}