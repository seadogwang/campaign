package com.company.campaign.model;

import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.segment.SegmentModelCallKind;
import com.company.campaign.builder.segment.SegmentPromptContract;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one fixed, versioned rendering of a {@link SegmentModelRequest} into a bounded Segment Intent prompt.
 *
 * <p>It is deliberately neutral — it lives outside every {@code dev} package — so the development Anthropic
 * adapter and the production structured-model adapter render the byte-identical prompt from the same accepted
 * request record and the same accepted {@link SegmentPromptContract}. Nothing here reads a clock, a random
 * source, the environment or a provider object.
 */
public final class SegmentIntentPrompt {

    private static final CanonicalJson JSON = new CanonicalJson();

    private SegmentIntentPrompt() {
    }

    /** The bounded prompt text of one safe Segment model request. */
    public static String render(SegmentModelRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("snapshot", request.metadataSnapshot());
        metadata.put("definitions", request.permittedDefinitions());
        metadata.put("eventSelectors", request.eventSelectors());
        return request.instructions()
                + "\nPrompt contract version: " + SegmentPromptContract.VERSION
                + "\nPrompt contract hash: " + SegmentPromptContract.HASH.value()
                + "\nLocale: " + request.localeTag()
                + (request.temporalContext() == null
                        ? ""
                        : "\nTemporal context: " + JSON.writeString(request.temporalContext()))
                + (request.modelDefaults() == null
                        ? ""
                        : "\nRegistered model defaults: " + JSON.writeString(request.modelDefaults()))
                + repairInstruction(request)
                + "\nUtterance: " + request.utterance()
                + "\nUtterance UTF-16 index guide: " + utf16IndexGuide(request.utterance())
                + "\nPermitted Metadata projection: " + JSON.writeString(metadata);
    }

    private static String repairInstruction(SegmentModelRequest request) {
        if (request.callContext().kind() != SegmentModelCallKind.SCHEMA_REPAIR) {
            return "";
        }
        return "\nSchema repair attempt: the previous JSON was rejected by the strict Intent decoder."
                + " Re-read the closed schema above, correct the schema violations identified by these stable"
                + " issue codes, and return one fresh raw JSON object only. For a window issue, never repeat"
                + " an illegal MONTH or YEAR rolling unit; preserve uncertainty through AMBIGUOUS_WINDOW and"
                + " legal window alternatives when the utterance does not determine one exact legal window."
                + " Issue codes: "
                + JSON.writeString(request.callContext().schemaIssueCodes());
    }

    private static String utf16IndexGuide(String utterance) {
        List<Map<String, Object>> guide = new ArrayList<>();
        for (int start = 0; start < utterance.length(); ) {
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
}
