package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The exact resolution policy configuration a resolution was produced under.
 *
 * <p>It pins the policy version, the optional registered default that supplies the reporting currency for a
 * money clause, the evidence-score profile and the immutable concept-to-definition mapping. Its content hash
 * covers all four, so a change to any of them changes the pin and makes an older clarification stale. The
 * concept mapping is the only policy evidence that grounds a clause by its machine concept alone: it is a
 * versioned, bounded whitelist, so an unknown concept maps to nothing and is never invented at resolve time.
 *
 * <p>It carries no tenant, program or user field: those belong to the host-authorized Metadata snapshot, not
 * to the policy.
 */
public record ResolutionPolicy(
        String policyVersion,
        String reportingCurrencyDefaultId,
        String scoreProfile,
        Map<String, String> conceptDefinitionIds,
        ContentHash contentHash
) {

    private static final Pattern REFERENCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern MACHINE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The score profile label current in this build; a weight change requires a new label. */
    public static final String CURRENT_SCORE_PROFILE = "tier-weights/2";

    public ResolutionPolicy {
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 128) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A resolution policy requires a bounded version");
        }
        if (reportingCurrencyDefaultId != null && !REFERENCE_ID.matcher(reportingCurrencyDefaultId).matches()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A policy default id is not a reference identifier");
        }
        if (scoreProfile == null || scoreProfile.isBlank() || scoreProfile.length() > 128) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A resolution policy requires a bounded score profile");
        }
        conceptDefinitionIds = canonicalConceptMappings(conceptDefinitionIds);
        ContentHash computed =
                computeHash(policyVersion, reportingCurrencyDefaultId, scoreProfile, conceptDefinitionIds);
        if (contentHash == null || !contentHash.equals(computed)) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A policy content hash must match its configuration");
        }
    }

    /** Publishes a policy with no concept mapping, computing the content hash over its exact configuration. */
    public static ResolutionPolicy publish(String policyVersion, String reportingCurrencyDefaultId) {
        return publish(policyVersion, reportingCurrencyDefaultId, Map.of());
    }

    /** Publishes a policy, computing the content hash over its exact configuration. */
    public static ResolutionPolicy publish(
            String policyVersion, String reportingCurrencyDefaultId, Map<String, String> conceptDefinitionIds) {
        Map<String, String> canonicalMappings = canonicalConceptMappings(conceptDefinitionIds);
        return new ResolutionPolicy(
                policyVersion,
                reportingCurrencyDefaultId,
                CURRENT_SCORE_PROFILE,
                canonicalMappings,
                computeHash(policyVersion, reportingCurrencyDefaultId, CURRENT_SCORE_PROFILE, canonicalMappings));
    }

    /**
     * The permitted stable definition id an exact concept machine code maps to, or {@code null} when the
     * concept is unmapped. The mapping is a bounded whitelist: it never establishes that the mapped definition
     * exists in the permitted catalog, only that the policy permits it.
     */
    public String conceptDefinitionId(String concept) {
        return concept == null ? null : conceptDefinitionIds.get(concept);
    }

    /** The exact material this policy's content hash covers. */
    public Map<String, Object> hashMaterial() {
        return material(policyVersion, reportingCurrencyDefaultId, scoreProfile, conceptDefinitionIds);
    }

    private static ContentHash computeHash(
            String policyVersion,
            String reportingCurrencyDefaultId,
            String scoreProfile,
            Map<String, String> conceptDefinitionIds) {
        return CANONICAL_JSON.hash(
                material(policyVersion, reportingCurrencyDefaultId, scoreProfile, conceptDefinitionIds));
    }

    private static Map<String, Object> material(
            String policyVersion,
            String reportingCurrencyDefaultId,
            String scoreProfile,
            Map<String, String> conceptDefinitionIds) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("policyVersion", policyVersion);
        material.put("reportingCurrencyDefaultId", reportingCurrencyDefaultId);
        material.put("scoreProfile", scoreProfile);
        material.put("conceptDefinitionIds", conceptDefinitionIds);
        return material;
    }

    /** Freezes and sorts the bounded concept mapping; every key and value must be a bounded exact identifier. */
    private static Map<String, String> canonicalConceptMappings(Map<String, String> mappings) {
        if (mappings == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A resolution policy requires a concept mapping");
        }
        if (mappings.size() > ResolutionLimits.MAX_POLICY_CONCEPT_MAPPINGS) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.POLICY_INVALID, "A concept mapping exceeds the entry limit");
        }
        Map<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String concept = entry.getKey();
            String definitionId = entry.getValue();
            if (concept == null || !MACHINE_CODE.matcher(concept).matches()) {
                throw ResolutionException.invalid(
                        ResolutionErrorCodes.POLICY_INVALID, "A concept mapping key is not a bounded machine code");
            }
            if (definitionId == null || !REFERENCE_ID.matcher(definitionId).matches()) {
                throw ResolutionException.invalid(
                        ResolutionErrorCodes.POLICY_INVALID,
                        "A concept mapping value is not a reference identifier");
            }
            canonical.put(concept, definitionId);
        }
        return Map.copyOf(canonical);
    }
}
