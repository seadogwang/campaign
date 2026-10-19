package com.company.campaign.production.model;

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
 * The host-supplied opt-in configuration of the default-off production structured-model adapter.
 *
 * <p>Nothing here has a default that could accidentally reach a provider: {@link #isEnabled()} is {@code false}
 * unless the host sets {@code campaign.production.model.enabled=true}, and the endpoint, the host allowlist, the
 * model id, the API version and the credential have no committed value at all. Every value comes from explicit
 * host configuration or the equivalent relaxed-binding environment variables.
 *
 * <p>Every bound is validated before any client is built. The endpoint must be HTTPS, must carry no query, no
 * fragment and no user info, and its host must be named by the explicit allowlist, so a configuration can never
 * silently redirect the adapter to an unknown host. {@link #toString()} redacts the endpoint, the credential and
 * the allowlist; {@link #configurationHash()} is a one-way canonical hash published as provider evidence and
 * never discloses any of them.
 */
@ConfigurationProperties(prefix = "campaign.production.model")
public class ProductionModelProperties {

    /** The fixed contract version of this adapter, published as provider evidence. */
    public static final String CONTRACT_VERSION = "campaign-production-model/1";

    /** The provider kind published as provider evidence. */
    public static final String PROVIDER_KIND = "anthropic-messages";

    /** The one Anthropic-compatible Messages API version this build sends. */
    public static final String ACCEPTED_API_VERSION = "2023-06-01";

    /** The largest accepted model id length. */
    public static final int MAX_MODEL_ID_LENGTH = 128;

    /** The largest accepted allowlist size. */
    public static final int MAX_ALLOWED_HOSTS = 8;

    /** The largest accepted response byte limit. */
    public static final int MAX_RESPONSE_BYTES_LIMIT = 1_048_576;

    /** The largest accepted input byte limit. */
    public static final int MAX_INPUT_BYTES_LIMIT = 1_048_576;

    /** The largest accepted bounded retry count. */
    public static final int MAX_ATTEMPTS_LIMIT = 5;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The literal loopback hosts for which a plaintext endpoint is representable, and never a real endpoint. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "localhost");

    private boolean enabled = false;
    private boolean requireHttps = true;
    private String endpoint;
    private List<String> allowedHosts = new ArrayList<>();
    private String modelId;
    private String apiVersion = ACCEPTED_API_VERSION;
    private String credential;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(60);
    private int maxResponseBytes = 65_536;
    private int maxInputBytes = 262_144;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(250);
    private Duration maxBackoff = Duration.ofSeconds(2);
    private Duration evidenceMaxAge = Duration.ofDays(7);
    private List<String> gatePins = new ArrayList<>();

    /** True only when the host explicitly opted into the production structured-model adapter. */
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

    /** The configured endpoint base URL; never printed. */
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

    /** The exact provider model id; never printed. */
    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    /** The Anthropic-compatible API version sent with every request. */
    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    /** The API credential; never printed or hashed in the clear. */
    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
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

    public int getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(int maxInputBytes) {
        this.maxInputBytes = maxInputBytes;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
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

    /** The bounded age a live model verification may have before it stops being current evidence. */
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

    /**
     * Validates the whole configuration, refusing an enabled adapter with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the production model off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        validateEndpoint();
        validateModel();
        if (credential == null || credential.isBlank() || credential.length() > 4_096) {
            throw invalid("credential");
        }
        requireDuration(connectTimeout, 250, 30_000, "connectTimeout");
        requireDuration(requestTimeout, 1_000, 300_000, "requestTimeout");
        requireRange(maxResponseBytes, 1_024, MAX_RESPONSE_BYTES_LIMIT, "maxResponseBytes");
        requireRange(maxInputBytes, 1_024, MAX_INPUT_BYTES_LIMIT, "maxInputBytes");
        requireRange(maxAttempts, 1, MAX_ATTEMPTS_LIMIT, "maxAttempts");
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

    private void validateEndpoint() {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException failure) {
            throw invalid("endpoint");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        if ((!https && !loopbackHttp) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            // HTTPS is required by default; a plaintext endpoint is representable only for an explicit loopback
            // host, which can never be a production endpoint.
            throw invalid("endpoint");
        }
        if (requireHttps && !https) {
            throw invalid("endpoint");
        }
        if (allowedHosts.isEmpty() || allowedHosts.size() > MAX_ALLOWED_HOSTS) {
            throw invalid("allowedHosts");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String entry : allowedHosts) {
            if (entry == null || entry.isBlank() || entry.length() > 253) {
                throw invalid("allowedHosts");
            }
            if (entry.toLowerCase(Locale.ROOT).equals(host)) {
                allowed = true;
            }
        }
        if (!allowed) {
            throw invalid("allowedHosts");
        }
    }

    private void validateModel() {
        if (modelId == null || modelId.isBlank() || modelId.length() > MAX_MODEL_ID_LENGTH) {
            throw invalid("modelId");
        }
        if (!ACCEPTED_API_VERSION.equals(apiVersion)) {
            throw invalid("apiVersion");
        }
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

    private static ProductionModelException invalid(String field) {
        return new ProductionModelException(
                ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID,
                "The opt-in production model configuration is missing or out of bounds: " + field);
    }

    /** The validated Messages endpoint, derived from the endpoint base URL exactly like the accepted boundary. */
    public URI messagesEndpoint() {
        validateEndpoint();
        URI base = URI.create(endpoint);
        String path = base.getPath() == null ? "" : base.getPath();
        path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        path = path.endsWith("/v1") ? path + "/messages" : path + "/v1/messages";
        try {
            return new URI(base.getScheme(), base.getAuthority(), path, null, null);
        } catch (Exception failure) {
            throw invalid("endpoint");
        }
    }

    /** The one-way fingerprint of the configured model id, safe to publish as evidence. */
    public ContentHash modelIdFingerprint() {
        if (modelId == null) {
            return ConfigurationMaterial.EMPTY_HASH;
        }
        return ContentHash.sha256(modelId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The one-way canonical hash of the whole effective configuration, safe to publish as provider evidence.
     *
     * <p>It is a hash, so it discloses none of the endpoint, the allowlist, the model id or the credential, yet
     * it pins the exact configuration a verification was performed under: rotating the credential, moving the
     * endpoint or changing a bound changes the hash.
     */
    public ContentHash configurationHash() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("providerKind", PROVIDER_KIND);
        material.put("contractVersion", CONTRACT_VERSION);
        material.put("endpoint", endpoint == null ? "" : endpoint);
        material.put("allowedHosts", List.copyOf(allowedHosts));
        material.put("modelId", modelId == null ? "" : modelId);
        material.put("apiVersion", apiVersion == null ? "" : apiVersion);
        material.put("credential", credential == null ? "" : credential);
        material.put("requireHttps", requireHttps);
        material.put("connectTimeoutMs", connectTimeout == null ? -1 : connectTimeout.toMillis());
        material.put("requestTimeoutMs", requestTimeout == null ? -1 : requestTimeout.toMillis());
        material.put("maxResponseBytes", maxResponseBytes);
        material.put("maxInputBytes", maxInputBytes);
        material.put("maxAttempts", maxAttempts);
        material.put("initialBackoffMs", initialBackoff == null ? -1 : initialBackoff.toMillis());
        material.put("maxBackoffMs", maxBackoff == null ? -1 : maxBackoff.toMillis());
        return CANONICAL_JSON.hash(material);
    }

    /** Redacted diagnostics: neither the endpoint, the allowlist, the model id nor the credential is printed. */
    @Override
    public String toString() {
        return "ProductionModelProperties[enabled=" + enabled
                + ", requireHttps=" + requireHttps
                + ", endpoint=<redacted>"
                + ", allowedHosts=" + allowedHosts.size()
                + ", modelId=<redacted>"
                + ", apiVersion=" + apiVersion
                + ", credential=<redacted>"
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + ", maxInputBytes=" + maxInputBytes
                + ", maxAttempts=" + maxAttempts
                + ", evidenceMaxAge=" + evidenceMaxAge
                + ", gatePins=" + gatePins.size() + ']';
    }

    /** The one fixed, value-free hash used when no model id is configured. */
    private static final class ConfigurationMaterial {

        private static final ContentHash EMPTY_HASH =
                ContentHash.sha256("production-model-unconfigured".getBytes(StandardCharsets.UTF_8));

        private ConfigurationMaterial() {
        }
    }
}