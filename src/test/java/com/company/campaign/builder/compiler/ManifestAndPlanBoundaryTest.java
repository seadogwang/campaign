package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.company.campaign.builder.testsupport.BuilderFixtures.MESSAGE;
import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirements 12-14: the Manifest is complete, non-forgeable and hash-bound; the semantic diff is minimal,
 * ordered and value-minimized; the ChangeSet is closed and non-executable; and the Plan is non-forgeable,
 * full-hash-derived, completely hash-bound, expiry-input-bound and redacted.
 */
class ManifestAndPlanBoundaryTest {

    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final String MESSAGE_VERSION = "message-template-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
            FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));

    // ------------------------------------------------------------------ requirement 12

    @Test
    void theManifestIsCompleteOrderedAndContainsNoOperationOrTrustedValue() {
        ResolvedComponent message = BuilderFixtures.component(
                "message-main", MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message"),
                Map.of("text", "hi"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a"))
                .build();

        CampaignPlan plan = compile(patch, List.of(message));
        ResolvedCampaignManifest manifest = plan.manifest();

        assertThat(manifest.components()).hasSize(2);
        assertThat(manifest.components().get(0)).isEqualTo(message);
        for (ResolvedComponent component : manifest.components()) {
            assertThat(component.logicalId()).isNotBlank();
            assertThat(component.contractVersion()).isNotBlank();
            assertThat(component.slot()).isNotNull();
            assertThat(component.value()).isNotNull();
            for (ResolvedCatalogReference reference : component.references()) {
                assertThat(reference.logicalId()).isNotBlank();
                assertThat(reference.versionId()).isNotBlank();
            }
        }
        assertThat(manifest.capabilityPins()).extracting(CapabilityPin::capabilityCode)
                .containsExactly(MESSAGE, SEGMENT);

        String canonical = new String(manifest.canonicalBytes(), StandardCharsets.UTF_8);
        assertThat(canonical)
                .doesNotContain("UPSERT", "DELETE", "MOVE", "clientRef", "segment-new", "authorized")
                .doesNotContain(BuilderFixtures.TENANT, BuilderFixtures.USER, BuilderFixtures.DATA_SCOPE,
                        BuilderFixtures.PROGRAM);
        assertThat(manifest.manifestHash())
                .isEqualTo(ContentHash.sha256(manifest.canonicalBytes()));
    }

    @Test
    void theManifestAndChangeSetAreNonForgeable() {
        assertNonForgeable(ResolvedCampaignManifest.class);
        assertNonForgeable(ChangeSet.class);
        assertNonForgeable(CampaignPlan.class);
    }

    @Test
    void theChangeSetExposesNoExecutionMethod() {
        Set<String> publicMethods = Arrays.stream(ChangeSet.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(publicMethods)
                .containsExactlyInAnyOrder("formatVersion", "nodes", "isEmpty", "size", "equals", "hashCode",
                        "toString");
        assertThat(Arrays.stream(ChangeSet.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type.getName().startsWith("java.util.function"));
    }

    // ------------------------------------------------------------------ requirement 13

    @Test
    void theSemanticDiffIsOrderedMinimalAndValueMinimized() {
        List<ResolvedComponent> current = List.of(
                component("a", SEGMENT, Map.of("spec", "a")),
                component("b", MESSAGE, Map.of("text", "one")),
                component("c", MESSAGE, Map.of("text", "two")));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(MESSAGE, "b", Map.of("text", "changed"))
                .delete("c")
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "n"))
                .move("a", com.company.campaign.builder.proposal.MoveAnchorKind.AFTER, "b")
                .build();
        CampaignPlan plan = compile(patch, current);

        List<ChangeKind> kinds = plan.semanticDiff().entries().stream().map(DiffEntry::kind).toList();
        assertThat(kinds).startsWith(ChangeKind.ADD);
        assertThat(kinds.indexOf(ChangeKind.UPDATE)).isLessThan(kinds.indexOf(ChangeKind.DELETE));

        Set<String> allowedFieldPaths = Set.of("component", "order", "capabilityCode", "contractVersion",
                "compositionSlot", "value", "references");
        for (DiffEntry entry : plan.semanticDiff().entries()) {
            assertThat(entry.fieldPaths()).isSubsetOf(allowedFieldPaths);
            assertThat(entry.fieldPaths()).isNotEmpty();
            assertThat(entry.logicalId()).isNotBlank();
        }
        String diffText = plan.semanticDiff().toString();
        assertThat(diffText).doesNotContain("changed", "one", "two", BuilderFixtures.TENANT);
    }

    @Test
    void anUpdateCarriesOnlyTheFactsThatChanged() {
        ResolvedComponent segment = component("a", SEGMENT, Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", "b")).build();
        CampaignPlan plan = compile(patch, List.of(segment));

        ChangeSetNode node = plan.changeSet().nodes().get(0);
        assertThat(node).isInstanceOf(ChangeSetNode.Update.class);
        List<AssetChange> changes = ((ChangeSetNode.Update) node).changes();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0)).isInstanceOf(AssetChange.ValueChanged.class);
        assertThat(plan.semanticDiff().entries().get(0).fieldPaths()).containsExactly("value");
    }

    @Test
    void aChangeSetIsNeverAcceptedAsCompilerInput() {
        // The only public entry point takes a Proposal; there is no overload, codec or setter for a ChangeSet.
        assertThat(Arrays.stream(CampaignCompiler.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type.equals(ChangeSet.class)
                        || type.equals(SemanticDiff.class)
                        || type.equals(ResolvedCampaignManifest.class));
    }

    // ------------------------------------------------------------------ requirement 14

    @Test
    void thePlanIdentifierIsDerivedFromAllSixtyFourDigestCharacters() {
        CampaignPlan plan = compile(defaultPatch(), List.of(component("a", SEGMENT, Map.of("spec", "a"))));

        assertThat(plan.planId()).hasSize(64);
        assertThat(plan.planHash().value()).isEqualTo("sha256:" + plan.planId());
        assertThat(plan.planId()).matches("[0-9a-f]{64}");
        assertThat(plan.planRef().planId()).isEqualTo(plan.planId());
        assertThat(plan.planRef().planHash()).isEqualTo(plan.planHash());
        assertThat(plan.planRef().expiresAt()).isEqualTo(BuilderFixtures.EXPIRES_AT);
    }

    @Test
    void thePlanHashCoversEverySemanticInputAndVersionDiscriminator() {
        CampaignPlan base = compile(defaultPatch(), List.of(component("a", SEGMENT, Map.of("spec", "a"))));
        String baseMaterial = new String(base.canonicalMaterialBytes(), StandardCharsets.UTF_8);
        assertThat(baseMaterial).contains(CompilerVersions.COMPILER_VERSION, CompilerVersions.PLAN_FORMAT_VERSION);
        assertThat(baseMaterial).contains(BuilderFixtures.TENANT, BuilderFixtures.USER);

        assertThat(compile(defaultPatch(), List.of(component("a", SEGMENT, Map.of("spec", "b")))).planHash())
                .isNotEqualTo(base.planHash());
        assertThat(compile(defaultPatch(), List.of(component("a", SEGMENT, Map.of("spec", "a")),
                component("b", MESSAGE, Map.of("text", "hi")))).planHash())
                .isNotEqualTo(base.planHash());
        assertThat(compile(patchWithLocale("fr-FR"), List.of(component("a", SEGMENT, Map.of("spec", "a"))))
                .planHash())
                .isNotEqualTo(base.planHash());

        FrozenCapabilityCatalog otherCatalog = BuilderFixtures.catalogWithHash(
                "sha256:" + "d".repeat(64),
                FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
                FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));
        CampaignProposalPatch otherPins = BuilderFixtures.editProposal().pins(otherCatalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", "a")).build();
        assertThat(compiler.compile(CompileRequest.edit(
                otherPins, BuilderFixtures.current(component("a", SEGMENT, Map.of("spec", "a"))), otherCatalog,
                policy, BuilderFixtures.instants(), BuilderFixtures.context())).planHash())
                .isNotEqualTo(base.planHash());

        FrozenAuthoringPolicy otherPolicy = BuilderFixtures.policy(request -> List.of(
                BuilderFixtures.warning("FIXTURE_ADVISORY")));
        CampaignProposalPatch otherPolicyPins = BuilderFixtures.editProposal().pins(catalog, otherPolicy)
                .upsert(SEGMENT, "a", Map.of("spec", "a")).build();
        assertThat(compiler.compile(CompileRequest.edit(
                otherPolicyPins, BuilderFixtures.current(component("a", SEGMENT, Map.of("spec", "a"))), catalog,
                otherPolicy, BuilderFixtures.instants(), BuilderFixtures.context())).planHash())
                .isNotEqualTo(base.planHash());

        CampaignPlan otherInstant = compiler.compile(CompileRequest.edit(
                defaultPatch(), BuilderFixtures.current(component("a", SEGMENT, Map.of("spec", "a"))), catalog,
                policy, new CompileInstants(Instant.parse("2026-10-03T00:05:00Z"),
                        Instant.parse("2026-10-03T00:20:00Z")),
                BuilderFixtures.context()));
        assertThat(otherInstant.planHash()).isNotEqualTo(base.planHash());

        CampaignPlan otherOwner = compiler.compile(CompileRequest.edit(
                defaultPatch(), BuilderFixtures.current(component("a", SEGMENT, Map.of("spec", "a"))), catalog,
                policy, BuilderFixtures.instants(), BuilderFixtures.contextWithUser("user-777")));
        assertThat(otherOwner.planHash()).isNotEqualTo(base.planHash());
    }

    @Test
    void thePlanDiagnosticsRedactTheTrustedIdentifiers() {
        CampaignPlan plan = compile(defaultPatch(), List.of(component("a", SEGMENT, Map.of("spec", "a"))));
        String diagnostics = plan.toString();
        assertThat(diagnostics)
                .doesNotContain(BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER,
                        BuilderFixtures.CAMPAIGN)
                .contains(plan.planId());
        assertThat(plan.manifest().toString()).doesNotContain(
                BuilderFixtures.TENANT, BuilderFixtures.USER, BuilderFixtures.DATA_SCOPE);
    }

    @Test
    void theCompileInstantsAreExplicitBoundedAndOrdered() {
        assertThatThrownBy(() -> new CompileInstants(BuilderFixtures.COMPILED_AT, BuilderFixtures.COMPILED_AT))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_INSTANTS_INVALID);
        assertThatThrownBy(() -> new CompileInstants(
                BuilderFixtures.COMPILED_AT, BuilderFixtures.COMPILED_AT.plusSeconds(7200)))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_INSTANTS_INVALID);
    }

    @Test
    void thePlanCarriesTheClientReferenceMappingAndBoundedExtensionSlots() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();
        CampaignPlan plan = compile(patch, List.of(component("a", SEGMENT, Map.of("spec", "a"))));

        assertThat(plan.clientRefBindings()).hasSize(1);
        assertThat(plan.clientRefBindings().get(0).clientRef()).isEqualTo("segment-new");
        assertThat(plan.previewCards()).isEmpty();
        assertThat(plan.riskSummary().riskCodes()).isEmpty();
    }

    @Test
    void compilingWithoutEveryRequiredInputFailsClosed() {
        assertThatThrownBy(() -> compiler.compile(null))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_INPUT_MISSING);
        assertThatThrownBy(() -> compiler.compileAndStore(CompileRequest.create(
                defaultCreatePatch(), catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()), null))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_INPUT_MISSING);
    }

    // ------------------------------------------------------------------ helpers

    private CampaignPlan compile(CampaignProposalPatch patch, List<ResolvedComponent> current) {
        return compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(12L, 3L, null, current), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private CampaignProposalPatch defaultPatch() {
        return BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", "a")).build();
    }

    private CampaignProposalPatch patchWithLocale(String locale) {
        return BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", "a")).locale(locale).build();
    }

    private CampaignProposalPatch defaultCreatePatch() {
        return BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
    }

    private static ResolvedComponent component(String logicalId, String capability, Map<String, Object> value) {
        String version = capability.equals(SEGMENT) ? SEGMENT_VERSION : MESSAGE_VERSION;
        CompositionSlot slot = capability.equals(SEGMENT)
                ? FixtureCapability.ordered("segment")
                : FixtureCapability.unorderedMulti("message");
        return BuilderFixtures.component(logicalId, capability, version, slot, value);
    }

    private static void assertNonForgeable(Class<?> type) {
        assertThat(Modifier.isFinal(type.getModifiers())).as("%s must be final", type).isTrue();
        assertThat(Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers())))
                .as("%s must have no public constructor", type)
                .isEmpty();
        assertThat(Arrays.stream(type.getDeclaredConstructors())
                .map(Constructor::getParameterCount)
                .count())
                .as("%s must declare its construction path", type)
                .isPositive();
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())
                        && type.isAssignableFrom(method.getReturnType())))
                .as("%s must have no public static factory", type)
                .isEmpty();
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        com.fasterxml.jackson.annotation.JsonCreator.class)))
                .as("%s must have no deserializer", type)
                .isEmpty();
        assertThat(Arrays.stream(type.getDeclaredClasses()).map(Class::getSimpleName))
                .as("%s must expose no builder or factory type", type)
                .noneMatch(name -> name.contains("Builder") || name.contains("Factory"));
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .as("%s must expose no executable operation", type)
                .noneMatch(name -> List.of("apply", "execute", "run", "mutate", "invoke", "save", "publish")
                        .contains(name));
    }
}