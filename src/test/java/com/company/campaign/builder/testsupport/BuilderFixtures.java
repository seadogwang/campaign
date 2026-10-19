package com.company.campaign.builder.testsupport;

import com.company.campaign.builder.compiler.AuthoringPolicyValidator;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.builder.proposal.ProposalSchemaVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared, deterministic fixtures for the T15 proposal/compiler/Plan tests. */
public final class BuilderFixtures {

    public static final String TENANT = "tenant-001";
    public static final String PROGRAM = "program-001";
    public static final String USER = "user-001";
    public static final String DATA_SCOPE = "scope-001";
    public static final String CAMPAIGN = "campaign-001";
    public static final long CAMPAIGN_REVISION = 12L;
    public static final String DRAFT = "draft-001";
    public static final long DRAFT_REVISION = 3L;
    public static final String PUBLISHED = "published-001";
    public static final String CATALOG_VERSION = "catalog-31";
    public static final String POLICY_VERSION = "policy-8";
    public static final Instant COMPILED_AT = Instant.parse("2026-10-03T00:00:00Z");
    public static final Instant EXPIRES_AT = Instant.parse("2026-10-03T00:15:00Z");

    public static final String MESSAGE = "message.template";
    public static final String SEGMENT = "segment.rule-based";
    public static final String FLOW = "flow.message";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private BuilderFixtures() {
    }

    // ------------------------------------------------------------------ trusted context

    public static TrustedExecutionContext context() {
        return context(TENANT, PROGRAM);
    }

    public static TrustedExecutionContext context(String tenant, String program) {
        return context(tenant, program, Set.of(CampaignPermission.values()));
    }

    public static TrustedExecutionContext context(
            String tenant,
            String program,
            Set<CampaignPermission> permissions) {
        VerifiedIdentity identity = new VerifiedIdentity(USER, "https://issuer.example", COMPILED_AT);
        PrincipalMembership membership = new PrincipalMembership(
                "membership-001", USER, tenant, program, DATA_SCOPE, MembershipStatus.ACTIVE, permissions);
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                tenant, program, USER, DATA_SCOPE, "snapshot-001", new LocaleTag("zh-CN"), ZoneId.of("Asia/Shanghai"),
                "CNY", COMPILED_AT, COMPILED_AT.minusSeconds(60));
        return TrustedExecutionContext.of(identity, membership, reference);
    }

    /** The same tenant/program membership, for a different verified user. */
    public static TrustedExecutionContext contextWithUser(String user) {
        VerifiedIdentity identity = new VerifiedIdentity(user, "https://issuer.example", COMPILED_AT);
        PrincipalMembership membership = new PrincipalMembership(
                "membership-001", user, TENANT, PROGRAM, DATA_SCOPE, MembershipStatus.ACTIVE,
                Set.of(CampaignPermission.values()));
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                TENANT, PROGRAM, user, DATA_SCOPE, "snapshot-001", new LocaleTag("zh-CN"),
                ZoneId.of("Asia/Shanghai"), "CNY", COMPILED_AT, COMPILED_AT.minusSeconds(60));
        return TrustedExecutionContext.of(identity, membership, reference);
    }

    public static CompileInstants instants() {
        return new CompileInstants(COMPILED_AT, EXPIRES_AT);
    }

    // ------------------------------------------------------------------ frozen inputs

    public static FrozenCapabilityCatalog catalog(CapabilityContract... contracts) {
        List<CapabilityContract> list = List.of(contracts);
        List<String> pins = new ArrayList<>();
        for (CapabilityContract contract : list) {
            pins.add(contract.capabilityCode() + '@' + contract.contractVersion()
                    + '#' + contract.slot() + '!' + contract.enabled());
        }
        ContentHash hash = ContentHash.sha256(CANONICAL_JSON.write(pins));
        return new FrozenCapabilityCatalog(CATALOG_VERSION, hash, list);
    }

    /** A catalog with the same version but a deliberately different hash. */
    public static FrozenCapabilityCatalog catalogWithHash(String hash, CapabilityContract... contracts) {
        return new FrozenCapabilityCatalog(CATALOG_VERSION, new ContentHash(hash), List.of(contracts));
    }

    public static FrozenAuthoringPolicy policy() {
        return policy(request -> List.of());
    }

    public static FrozenAuthoringPolicy policy(AuthoringPolicyValidator validator) {
        return policy(POLICY_VERSION, validator);
    }

    /** A frozen policy under an explicit version, so a policy-version change is expressible. */
    public static FrozenAuthoringPolicy policy(String version, AuthoringPolicyValidator validator) {
        ContentHash hash = ContentHash.sha256(CANONICAL_JSON.write(List.of(version, "v1")));
        return new FrozenAuthoringPolicy(version, hash, validator);
    }

    /** A policy that reports one non-blocking advisory issue. */
    public static FrozenAuthoringPolicy policyWithWarnings() {
        return policy(request -> List.of(warning("FIXTURE_ADVISORY")));
    }

    /** A policy that reports one blocking issue. */
    public static FrozenAuthoringPolicy blockingPolicy() {
        return policy(request -> List.of(Issue.error("FIXTURE_BLOCKING", "blocked")));
    }

    public static Issue warning(String code) {
        return new Issue(code, com.company.campaign.contracts.IssueSeverity.WARNING, null, null, null, "advisory",
                Map.of());
    }

    // ------------------------------------------------------------------ proposal

    public static ProposalBuilder proposal(ProposalMode mode) {
        return new ProposalBuilder(mode);
    }

    public static ProposalBuilder createProposal() {
        return new ProposalBuilder(ProposalMode.CREATE);
    }

    public static ProposalBuilder editProposal() {
        return new ProposalBuilder(ProposalMode.EDIT);
    }

    /** Fluent builder that fills the frozen catalog/policy pins a patch must declare. */
    public static final class ProposalBuilder {

        private final ProposalMode mode;
        private String campaignId = CAMPAIGN;
        private Long campaignRevision = CAMPAIGN_REVISION;
        private String catalogVersion = CATALOG_VERSION;
        private ContentHash catalogHash;
        private String policyVersion = POLICY_VERSION;
        private ContentHash policyHash;
        private final List<ProposalOperation> operations = new ArrayList<>();
        private LocaleTag locale;
        private com.company.campaign.builder.proposal.JourneyTopologyMutation journeyTopology;

        private ProposalBuilder(ProposalMode mode) {
            this.mode = mode;
            if (mode == ProposalMode.CREATE) {
                this.campaignId = null;
                this.campaignRevision = null;
            }
        }

        public ProposalBuilder pins(FrozenCapabilityCatalog catalog, FrozenAuthoringPolicy policy) {
            this.catalogVersion = catalog.catalogVersion();
            this.catalogHash = catalog.catalogHash();
            this.policyVersion = policy.policyVersion();
            this.policyHash = policy.policyHash();
            return this;
        }

        public ProposalBuilder campaignId(String value) {
            this.campaignId = value;
            return this;
        }

        public ProposalBuilder campaignRevision(Long value) {
            this.campaignRevision = value;
            return this;
        }

        public ProposalBuilder locale(String value) {
            this.locale = value == null ? null : new LocaleTag(value);
            return this;
        }

        public ProposalBuilder upsertNew(String capability, String clientRef, Map<String, Object> value) {
            operations.add(ProposalOperation.upsertNew(capability, clientRef, CapabilityPayload.of(value)));
            return this;
        }

        public ProposalBuilder upsert(String capability, String logicalId, Map<String, Object> value) {
            operations.add(ProposalOperation.upsertExisting(capability, logicalId, CapabilityPayload.of(value)));
            return this;
        }

        public ProposalBuilder delete(String logicalId) {
            operations.add(ProposalOperation.delete(logicalId));
            return this;
        }

        public ProposalBuilder move(String logicalId, MoveAnchorKind kind, String anchor) {
            operations.add(ProposalOperation.move(
                    com.company.campaign.builder.proposal.ProposalIdentity.ofLogicalId(logicalId), kind, anchor));
            return this;
        }

        public ProposalBuilder moveRef(String clientRef, MoveAnchorKind kind, String anchor) {
            operations.add(ProposalOperation.move(
                    com.company.campaign.builder.proposal.ProposalIdentity.ofClientRef(clientRef), kind, anchor));
            return this;
        }

        public ProposalBuilder raw(ProposalOperation operation) {
            operations.add(operation);
            return this;
        }

        /** Attaches the one optional Journey-topology mutation, or leaves the topology to be inherited. */
        public ProposalBuilder journeyTopology(
                com.company.campaign.builder.proposal.JourneyTopologyMutation mutation) {
            this.journeyTopology = mutation;
            return this;
        }

        public CampaignProposalPatch build() {
            if (catalogHash == null || policyHash == null) {
                throw new IllegalStateException("call pins(catalog, policy) before build()");
            }
            ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                    campaignRevision, catalogVersion, catalogHash, policyVersion, policyHash);
            return new CampaignProposalPatch(
                    journeyTopology == null
                            ? ProposalSchemaVersions.LEGACY_V1_3
                            : ProposalSchemaVersions.CURRENT,
                    mode,
                    campaignId,
                    baseRevisions,
                    operations,
                    new ProposalClientContext(locale),
                    journeyTopology);
        }
    }

    // ------------------------------------------------------------------ current campaign

    public static TrustedCurrentCampaign current(ResolvedComponent... components) {
        return current(CAMPAIGN_REVISION, DRAFT_REVISION, null, List.of(components));
    }

    public static TrustedCurrentCampaign current(long campaignRevision, ResolvedComponent... components) {
        return current(campaignRevision, DRAFT_REVISION, null, List.of(components));
    }

    public static TrustedCurrentCampaign forkedCurrent(
            com.company.campaign.builder.compiler.PublishedIdentity forkBase,
            ResolvedComponent... components) {
        return current(CAMPAIGN_REVISION, DRAFT_REVISION, forkBase, List.of(components));
    }

    public static TrustedCurrentCampaign current(
            long campaignRevision,
            long draftRevision,
            com.company.campaign.builder.compiler.PublishedIdentity forkBase,
            List<ResolvedComponent> components) {
        return new TrustedCurrentCampaign(
                TENANT, PROGRAM, CAMPAIGN, campaignRevision,
                new DraftIdentity(DRAFT, draftRevision), forkBase, components);
    }

    public static ResolvedComponent component(String logicalId, String capability, String version, Map<String, Object> value) {
        return new ResolvedComponent(
                logicalId, capability, version, FixtureCapability.unorderedMulti("slot-" + capability),
                CapabilityPayload.of(value), List.of());
    }

    public static ResolvedComponent component(
            String logicalId,
            String capability,
            String version,
            CompositionSlot slot,
            Map<String, Object> value) {
        return new ResolvedComponent(logicalId, capability, version, slot, CapabilityPayload.of(value), List.of());
    }

    public static ResolvedComponent component(
            String logicalId,
            String capability,
            String version,
            CompositionSlot slot,
            Map<String, Object> value,
            List<ResolvedCatalogReference> references) {
        return new ResolvedComponent(logicalId, capability, version, slot, CapabilityPayload.of(value), references);
    }

    public static Map<String, Object> value(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    public static Map<String, Object> emptyValue() {
        return Map.of();
    }

    public static ResolvedCatalogReference reference(String code, String kind, String logicalId, String versionId) {
        return new ResolvedCatalogReference(code, kind, logicalId, versionId);
    }

    public static SchemaVersion schemaVersion() {
        return ContractVersions.CURRENT;
    }
}
