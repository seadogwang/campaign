package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlRequest;
import com.company.campaign.builder.segment.SemanticCandidate;
import com.company.campaign.builder.segment.SemanticDraft;
import com.company.campaign.builder.segment.SemanticDraftCodec;
import com.company.campaign.builder.segment.SemanticDraftPromptContract;
import com.company.campaign.builder.segment.SemanticFactDraft;
import com.company.campaign.builder.segment.SemanticSlot;
import com.company.campaign.builder.segment.SemanticSlotStatus;
import com.company.campaign.builder.segment.SemanticSlotType;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.segment.SegmentModelRequest.MetadataProjection;
import com.company.campaign.builder.segment.SegmentModelRequestFactory;
import com.company.campaign.builder.segment.StagedClarificationBatch;
import com.company.campaign.builder.segment.StagedClarificationBatchFactory;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Two-pass development slice for source-grounded understanding and consolidated clarification. */
public final class DevelopmentStagedUnderstandingService {

    private static final CanonicalJson JSON = new CanonicalJson();

    private final DevelopmentStructuredModelPort modelPort;
    private final SemanticDraftCodec codec = new SemanticDraftCodec();
    private final SegmentModelRequestFactory requestFactory = new SegmentModelRequestFactory();
    private final StagedClarificationBatchFactory clarificationFactory = new StagedClarificationBatchFactory();

    public DevelopmentStagedUnderstandingService(DevelopmentStructuredModelPort modelPort) {
        this.modelPort = Objects.requireNonNull(modelPort, "modelPort");
    }

    public DevelopmentStagedUnderstandingResult analyze(DevelopmentAiToSqlRequest request) {
        Objects.requireNonNull(request, "request");
        SemanticDraft discovery = codec.parse(
                modelPort.invokeStructured(discoveryPrompt(request)), request.utterance());

        SegmentModelRequest completeProjection = requestFactory.build(
                request.utterance(), request.localeTag(), request.permittedSnapshot(),
                request.resolutionPolicy(), request.trustedContext().snapshotRef().asOf(),
                request.trustedContext().snapshotRef().timezone(), request.registeredDefaults());
        MetadataSelection selection = selectMetadata(discovery, completeProjection);
        SemanticDraft refined = codec.parse(
                modelPort.invokeStructured(refinementPrompt(request, discovery, completeProjection, selection)),
                request.utterance());
        requireStableFacts(discovery, refined);
        StagedClarificationBatch batch = clarificationFactory.create(refined);
        return new DevelopmentStagedUnderstandingResult(
                discovery, refined, batch,
                SemanticDraftPromptContract.VERSION,
                SemanticDraftPromptContract.HASH.value());
    }

    String discoveryPrompt(DevelopmentAiToSqlRequest request) {
        return SemanticDraftPromptContract.TEXT
                + "\nPrompt contract version: " + SemanticDraftPromptContract.VERSION
                + "\nPrompt contract hash: " + SemanticDraftPromptContract.HASH.value()
                + "\nStage: DISCOVERY"
                + "\nLocale: " + request.localeTag()
                + "\nTemporal context: " + JSON.writeString(Map.of(
                        "asOf", request.trustedContext().snapshotRef().asOf().toString(),
                        "timezone", request.trustedContext().snapshotRef().timezone().getId()))
                + "\nUtterance: " + request.utterance()
                + "\nUtterance UTF-16 index guide: " + utf16IndexGuide(request.utterance());
    }

    String refinementPrompt(
            DevelopmentAiToSqlRequest request,
            SemanticDraft discovery,
            SegmentModelRequest projection,
            MetadataSelection selection) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("snapshot", projection.metadataSnapshot());
        metadata.put("definitions", selection.definitions());
        metadata.put("eventSelectors", selection.eventSelectors());
        metadata.put("registeredDefaults", projection.modelDefaults());
        return SemanticDraftPromptContract.TEXT
                + "\nPrompt contract version: " + SemanticDraftPromptContract.VERSION
                + "\nPrompt contract hash: " + SemanticDraftPromptContract.HASH.value()
                + "\nStage: METADATA_REFINEMENT"
                + "\nLocale: " + request.localeTag()
                + "\nTemporal context: " + JSON.writeString(Map.of(
                        "asOf", request.trustedContext().snapshotRef().asOf().toString(),
                        "timezone", request.trustedContext().snapshotRef().timezone().getId()))
                + "\nUtterance: " + request.utterance()
                + "\nFrozen discovery draft: " + JSON.writeString(discovery)
                + "\nPermitted structured Metadata: " + JSON.writeString(metadata);
    }

    MetadataSelection selectMetadata(SemanticDraft discovery, SegmentModelRequest projection) {
        EnumSet<MetadataKind> kinds = EnumSet.noneOf(MetadataKind.class);
        boolean includeEverything = false;
        boolean includeEventSelectors = false;
        for (SemanticFactDraft fact : discovery.facts()) {
            for (SemanticSlot slot : fact.slots()) {
                if (slot.slotType() == SemanticSlotType.EVENT_SELECTOR) {
                    includeEventSelectors = true;
                }
                if (slot.slotType() != SemanticSlotType.CAPABILITY_TYPE) {
                    continue;
                }
                if (slot.status() == SemanticSlotStatus.RESOLVED) {
                    CapabilitySelection selected = capability(slot.value());
                    includeEverything |= selected.unknown();
                    includeEventSelectors |= selected.eventSelectors();
                    kinds.addAll(selected.kinds());
                } else if (slot.status() == SemanticSlotStatus.AMBIGUOUS) {
                    for (SemanticCandidate candidate : slot.candidates()) {
                        CapabilitySelection selected = capability(candidate.value());
                        includeEverything |= selected.unknown();
                        includeEventSelectors |= selected.eventSelectors();
                        kinds.addAll(selected.kinds());
                    }
                } else {
                    includeEverything = true;
                }
            }
        }
        if (includeEverything || kinds.isEmpty()) {
            kinds = EnumSet.allOf(MetadataKind.class);
            includeEventSelectors = true;
        }

        Set<String> includedIds = new LinkedHashSet<>();
        for (MetadataProjection definition : projection.permittedDefinitions()) {
            if (kinds.contains(definition.kind())) {
                includedIds.add(definition.stableId());
            }
        }
        if (kinds.contains(MetadataKind.METRIC)) {
            projection.permittedDefinitions().stream()
                    .filter(definition -> definition.kind() == MetadataKind.METRIC)
                    .filter(definition -> includedIds.contains(definition.stableId()))
                    .flatMap(definition -> definition.metricFilterDimensions().stream())
                    .forEach(dimension -> includedIds.add(dimension.attributeId()));
        }
        if (includeEventSelectors) {
            projection.eventSelectors().stream()
                    .flatMap(selector -> selector.bindings().stream())
                    .flatMap(binding -> binding.scopeFilterDimensions().stream())
                    .forEach(dimension -> includedIds.add(dimension.attributeId()));
        }

        List<MetadataProjection> definitions = projection.permittedDefinitions().stream()
                .filter(definition -> includedIds.contains(definition.stableId()))
                .toList();
        return new MetadataSelection(
                definitions,
                includeEventSelectors ? projection.eventSelectors() : List.of());
    }

    private static CapabilitySelection capability(String value) {
        return switch (value) {
            case "ATTRIBUTE" -> new CapabilitySelection(EnumSet.of(MetadataKind.ATTRIBUTE), false, false);
            case "METRIC", "METRIC_EXPRESSION" ->
                    new CapabilitySelection(EnumSet.of(MetadataKind.METRIC), false, false);
            case "RELATION" -> new CapabilitySelection(EnumSet.of(MetadataKind.RELATION), false, false);
            case "EVENT" -> new CapabilitySelection(EnumSet.of(MetadataKind.RELATION), true, false);
            default -> new CapabilitySelection(EnumSet.noneOf(MetadataKind.class), false, true);
        };
    }

    private static void requireStableFacts(SemanticDraft discovery, SemanticDraft refined) {
        if (discovery.facts().size() != refined.facts().size()) {
            throw new IllegalArgumentException("Metadata refinement changed the semantic fact set");
        }
        Map<String, SemanticFactDraft> firstById = new LinkedHashMap<>();
        discovery.facts().forEach(fact -> firstById.put(fact.factId(), fact));
        for (SemanticFactDraft fact : refined.facts()) {
            SemanticFactDraft first = firstById.get(fact.factId());
            if (first == null || !first.sourceSpan().equals(fact.sourceSpan())) {
                throw new IllegalArgumentException("Metadata refinement changed a fact identity or source span");
            }
        }
    }

    private static String utf16IndexGuide(String utterance) {
        List<Map<String, Object>> guide = new ArrayList<>();
        for (int start = 0; start < utterance.length();) {
            int codePoint = utterance.codePointAt(start);
            int end = start + Character.charCount(codePoint);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("start", start);
            entry.put("end", end);
            entry.put("text", utterance.substring(start, end));
            guide.add(entry);
            start = end;
        }
        return JSON.writeString(guide);
    }

    record MetadataSelection(
            List<MetadataProjection> definitions,
            List<SegmentModelRequest.EventSelectorProjection> eventSelectors) {
    }

    private record CapabilitySelection(
            EnumSet<MetadataKind> kinds,
            boolean eventSelectors,
            boolean unknown) {
    }
}
