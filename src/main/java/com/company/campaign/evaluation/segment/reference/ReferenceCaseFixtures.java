package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The trusted fixtures one reference-corpus case declares, one slot per fixture role.
 *
 * <p>Each slot names a fixture identity the corpus fixture catalog must declare with the matching role, or
 * is absent. Absence is meaningful and is never defaulted: a case that drives the reference document path
 * declares a Metadata snapshot, a version catalog and a trusted context, while the one case that proves a
 * reference without a trusted resolution context deliberately declares no catalog, and both facts are visible
 * in the corpus rather than implied by the loader.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCaseFixtures(
        String metadataFixture,
        String catalogFixture,
        String dataScopeFixture,
        String programPolicyFixture,
        String referencePolicyFixture,
        String directoryFixture,
        String membershipFixture,
        String retentionPolicyFixture,
        String contextFixture
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceCaseFixtures {
        requireOptionalId(metadataFixture, "metadataFixture");
        requireOptionalId(catalogFixture, "catalogFixture");
        requireOptionalId(dataScopeFixture, "dataScopeFixture");
        requireOptionalId(programPolicyFixture, "programPolicyFixture");
        requireOptionalId(referencePolicyFixture, "referencePolicyFixture");
        requireOptionalId(directoryFixture, "directoryFixture");
        requireOptionalId(membershipFixture, "membershipFixture");
        requireOptionalId(retentionPolicyFixture, "retentionPolicyFixture");
        requireOptionalId(contextFixture, "contextFixture");
    }

    /** The fixture declared for one role, or {@code null} when the case declares none. */
    public String forRole(ReferenceFixtureRole role) {
        return declared().get(role);
    }

    /** Every declared slot, keyed by the role it fills, in role declaration order. */
    public Map<ReferenceFixtureRole, String> declared() {
        Map<ReferenceFixtureRole, String> declared = new LinkedHashMap<>();
        put(declared, ReferenceFixtureRole.METADATA_SNAPSHOT, metadataFixture);
        put(declared, ReferenceFixtureRole.VERSION_CATALOG, catalogFixture);
        put(declared, ReferenceFixtureRole.DATA_SCOPE, dataScopeFixture);
        put(declared, ReferenceFixtureRole.PROGRAM_POLICY, programPolicyFixture);
        put(declared, ReferenceFixtureRole.REFERENCE_POLICY, referencePolicyFixture);
        put(declared, ReferenceFixtureRole.DISCOVERY_DIRECTORY, directoryFixture);
        put(declared, ReferenceFixtureRole.MEMBERSHIP_DATASET, membershipFixture);
        put(declared, ReferenceFixtureRole.RETENTION_POLICY, retentionPolicyFixture);
        put(declared, ReferenceFixtureRole.EXECUTION_CONTEXT, contextFixture);
        return declared;
    }

    /** The declared fixture identities, in role order, for compact reporting. */
    public List<String> declaredIds() {
        return new ArrayList<>(declared().values());
    }

    private static void put(
            Map<ReferenceFixtureRole, String> declared, ReferenceFixtureRole role, String fixtureId) {
        if (fixtureId != null) {
            declared.put(role, fixtureId);
        }
    }

    private static void requireOptionalId(String value, String field) {
        if (value != null && !REFERENCE_ID.matcher(value).matches()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "Fixture slot '" + field + "' must name a bounded fixture identity or be absent",
                    Map.of("field", field));
        }
    }
}