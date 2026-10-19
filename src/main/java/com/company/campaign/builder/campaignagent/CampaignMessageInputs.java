package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.compiler.CapabilityPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The host-owned typed inputs of one Message requirement.
 *
 * <p>The host — never the routing model — decides which exact template reference and which bounded variable
 * values a Message component would use: a Tool argument is host evidence, and the routing vocabulary has no
 * field that could carry one. An absent reference means the runner must ask a typed clarification rather than
 * fabricate an artifact.
 *
 * <p>This contract is one closed, explicit one-of: either a catalog <em>reference</em> shape — an exact
 * template {@code referenceCode} plus its bounded {@code variables} object — or an <em>inline draft</em>
 * shape — a canonical {@link Channel} plus the exact bounded user-authored {@code body}. The two shapes are
 * mutually exclusive: a value that carries both, or only part of one, cannot be constructed, so
 * {@link #resolved()} is true only for one complete valid shape and the runner can never be handed a
 * half-resolved input.
 */
public record CampaignMessageInputs(
        String referenceCode, CapabilityPayload variables, List<InlineDraft> inlineDrafts) {

    /** The maximum number of UTF-16 units of one user-authored inline message body. */
    public static final int MAX_BODY_LENGTH = 4096;

    /**
     * The closed set of canonical delivery channels an inline draft may name.
     *
     * <p>This is a generic machine-code vocabulary, never a language rule: the model maps the user's own words
     * to one of these declared codes, and every other value is refused as an unsupported channel.
     */
    public enum Channel {
        SMS,
        EMAIL,
        PUSH,
        IN_APP,
        WECHAT;

        /** The declared channel whose canonical machine code equals the argument, or null when none does. */
        public static Channel fromCode(String code) {
            if (code == null) {
                return null;
            }
            for (Channel channel : values()) {
                if (channel.name().equals(code)) {
                    return channel;
                }
            }
            return null;
        }
    }

    /** The bounded inline draft facts: one canonical channel and the exact bounded user-authored body. */
    public record InlineDraft(Channel channel, String body) {

        public InlineDraft {
            Objects.requireNonNull(channel, "channel");
            if (body == null || body.isEmpty() || body.length() > MAX_BODY_LENGTH) {
                throw new IllegalArgumentException("An inline message draft requires one bounded body.");
            }
        }
    }

    /** Enforces the closed one-of at the only construction boundary. */
    public CampaignMessageInputs {
        inlineDrafts = inlineDrafts == null ? List.of() : List.copyOf(new ArrayList<>(inlineDrafts));
        boolean referencePart = referenceCode != null || variables != null;
        if (referencePart && !inlineDrafts.isEmpty()) {
            throw new IllegalArgumentException("A message input is either a reference shape or an inline shape.");
        }
        if (referencePart && (referenceCode == null || variables == null)) {
            throw new IllegalArgumentException(
                    "A message reference shape requires both its reference code and its variables.");
        }
        java.util.EnumSet<Channel> channels = java.util.EnumSet.noneOf(Channel.class);
        for (InlineDraft inline : inlineDrafts) {
            Objects.requireNonNull(inline, "inlineDraft");
            if (!channels.add(inline.channel())) {
                throw new IllegalArgumentException("An inline message channel may be declared only once.");
            }
        }
    }

    /** The exact two-component reference constructor, kept source-compatible with the accepted host wiring. */
    public CampaignMessageInputs(String referenceCode, CapabilityPayload variables) {
        this(referenceCode, variables, List.of());
    }

    /** Source-compatible constructor for one inline draft. */
    public CampaignMessageInputs(String referenceCode, CapabilityPayload variables, InlineDraft inline) {
        this(referenceCode, variables, inline == null ? List.of() : List.of(inline));
    }

    /** The bounded "nothing resolved yet" input, which makes the runner ask for the template. */
    public static CampaignMessageInputs unresolved() {
        return new CampaignMessageInputs(null, null, List.of());
    }

    /** The complete inline draft shape over one canonical channel and one exact bounded body. */
    public static CampaignMessageInputs ofInline(Channel channel, String body) {
        return new CampaignMessageInputs(null, null, List.of(new InlineDraft(channel, body)));
    }

    /** The complete multi-channel inline shape, in canonical channel order. */
    public static CampaignMessageInputs ofInlineDrafts(List<InlineDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return unresolved();
        }
        List<InlineDraft> ordered = new ArrayList<>(drafts);
        ordered.sort(java.util.Comparator.comparing(InlineDraft::channel));
        return new CampaignMessageInputs(null, null, ordered);
    }

    /** The fixed server-owned component identity for one inline delivery channel. */
    public static String componentRef(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        return "campaign-message-" + channel.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** True when exactly one complete shape — a catalog reference or an inline draft — was resolved. */
    public boolean resolved() {
        return referenceCode != null || !inlineDrafts.isEmpty();
    }

    /** True for the complete catalog-reference shape. */
    public boolean isReference() {
        return referenceCode != null && variables != null;
    }

    /** True for the complete inline-draft shape. */
    public boolean isInline() {
        return !inlineDrafts.isEmpty();
    }

    /** The single inline draft, or null when unresolved, referenced, or multi-channel. */
    public InlineDraft inline() {
        return inlineDrafts.size() == 1 ? inlineDrafts.get(0) : null;
    }

    /** The inline channel, or null for a reference or unresolved input. */
    public Channel channel() {
        InlineDraft inline = inline();
        return inline == null ? null : inline.channel();
    }

    /** The exact inline body, or null for a reference or unresolved input. */
    public String body() {
        InlineDraft inline = inline();
        return inline == null ? null : inline.body();
    }
}
