package com.company.campaign.builder.segment;

import com.company.campaign.segment.intent.SourceSpanValidator;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.contracts.SourceSpan;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict fail-closed parser for untrusted staged semantic-draft model output. */
public final class SemanticDraftCodec {

    private final CanonicalJson json = new CanonicalJson();

    public SemanticDraft parse(byte[] raw, String utterance) {
        if (raw == null || raw.length == 0 || raw.length > SegmentModelLimits.MAX_MODEL_OUTPUT_BYTES) {
            throw new IllegalArgumentException("The semantic draft response has an invalid size");
        }
        String text = decode(raw).strip();
        if (text.isEmpty() || text.charAt(0) != '{' || text.contains("```")) {
            throw new IllegalArgumentException("The semantic draft response must be one raw JSON object");
        }
        SemanticDraft draft = json.read(text, SemanticDraft.class);
        return groundAndValidate(draft, utterance);
    }

    private static SemanticDraft groundAndValidate(SemanticDraft draft, String utterance) {
        List<SemanticFactDraft> facts = new ArrayList<>(draft.facts().size());
        for (SemanticFactDraft fact : draft.facts()) {
            SourceSpan grounded = ground(fact.sourceSpan(), utterance);
            SourceSpanValidator.validate(utterance, grounded);
            facts.add(grounded.equals(fact.sourceSpan())
                    ? fact
                    : new SemanticFactDraft(fact.factId(), grounded, fact.searchTerms(), fact.slots()));
        }
        return facts.equals(draft.facts())
                ? draft
                : new SemanticDraft(draft.draftVersion(), List.copyOf(facts), draft.globalSlots());
    }

    private static SourceSpan ground(SourceSpan span, String utterance) {
        if (span.start() >= 0 && span.end() <= utterance.length()
                && span.end() - span.start() == span.text().length()
                && utterance.regionMatches(span.start(), span.text(), 0, span.text().length())) {
            return span;
        }
        int first = utterance.indexOf(span.text());
        if (first < 0 || utterance.indexOf(span.text(), first + 1) >= 0) {
            return span;
        }
        return new SourceSpan(span.text(), first, first + span.text().length());
    }

    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("The semantic draft response is not valid UTF-8", failure);
        }
    }
}
