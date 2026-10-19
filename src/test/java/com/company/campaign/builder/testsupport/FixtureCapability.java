package com.company.campaign.builder.testsupport;

import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.RuntimeCompatibility;

import java.util.List;
import java.util.function.Function;

/**
 * Test-only capability contract fixture.
 *
 * <p>T15 production deliberately implements no concrete capability semantics — {@code segment.rule-based} and
 * every later capability pack belong to T16/T19. The compiler boundary is therefore exercised with these
 * fixtures, which can be made to normalize, to resolve catalog references, to fail, to be disabled and to
 * declare any composition slot.
 */
public record FixtureCapability(
        String capabilityCode,
        String contractVersion,
        CompositionSlot slot,
        boolean enabled,
        RuntimeCompatibility runtimeCompatibility,
        Function<CapabilityRequest, CapabilityResolution> resolver) implements CapabilityContract {

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        return resolver.apply(request);
    }

    /** A contract that normalizes nothing and resolves no reference. */
    public static FixtureCapability passThrough(String code, String version, CompositionSlot slot) {
        return new FixtureCapability(code, version, slot, true, runtime(), request -> resolve(request.payload()));
    }

    /** A contract that adds a normalized marker field to every payload. */
    public static FixtureCapability normalizing(String code, String version, CompositionSlot slot) {
        return new FixtureCapability(code, version, slot, true, runtime(), request -> {
            CapabilityPayload source = request.payload();
            java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>(source.fields());
            fields.put("normalized", true);
            return resolve(CapabilityPayload.of(fields));
        });
    }

    /** A contract that resolves one fixed catalog reference. */
    public static FixtureCapability referencing(
            String code,
            String version,
            CompositionSlot slot,
            ResolvedCatalogReference reference) {
        return new FixtureCapability(
                code, version, slot, true, runtime(), request -> resolve(request.payload(), List.of(reference)));
    }

    /** A contract that always fails with one bounded issue. */
    public static FixtureCapability failing(String code, String version, CompositionSlot slot, String issueCode) {
        return new FixtureCapability(code, version, slot, true, runtime(), request ->
                new CapabilityResolution.Failed(
                        List.of(com.company.campaign.contracts.Issue.error(issueCode, "Capability rejected"))));
    }

    /** A contract that declares a different contract version than the catalog pin expects. */
    public static FixtureCapability ofVersion(String code, String version, CompositionSlot slot) {
        return passThrough(code, version, slot);
    }

    public static CompositionSlot ordered(String code) {
        return new CompositionSlot(code, true, false);
    }

    public static CompositionSlot singleton(String code) {
        return new CompositionSlot(code, false, true);
    }

    public static CompositionSlot unorderedMulti(String code) {
        return new CompositionSlot(code, false, false);
    }

    public static RuntimeCompatibility runtime() {
        return new RuntimeCompatibility("campaign-runtime/1.0", true, true);
    }

    public static CapabilityResolution resolve(CapabilityPayload payload) {
        return resolve(payload, List.of());
    }

    public static CapabilityResolution resolve(
            CapabilityPayload payload,
            List<ResolvedCatalogReference> references) {
        return new CapabilityResolution.Resolved(payload, references);
    }
}