package com.company.campaign.builder.capability.message;

import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;

import java.util.Optional;

/**
 * A small dedicated public resolver that maps one canonical channel to an enabled,
 * runtime-available template version from a frozen catalog.
 *
 * <p>The resolver iterates the catalog and selects the first version whose
 * {@code enabled == true}, {@code runtimeAvailable == true} and
 * {@code channel} matches the requested channel. An absent, disabled,
 * runtime-unavailable or channel-mismatched version, or a null catalog,
 * returns empty rather than throwing, so the caller — typically the Message
 * Skill runner in the campaign-agent package — can produce a component-owned
 * {@code NeedsInput} clarification instead of a terminal exception.
 *
 * <p>This class is deliberately kept separate from
 * {@link MessageTemplatePack} to avoid widening the pack's public surface
 * with resolution semantics that are only needed by the campaign-agent
 * orchestration layer.
 */
public final class MessageInlineTemplateResolver {

    private MessageInlineTemplateResolver() {
    }

    /**
     * Resolves one enabled, runtime-available template version for the given canonical channel.
     *
     * @param catalog the frozen message template catalog, or {@code null}
     * @param channel the canonical channel machine code (e.g. {@code "WECHAT"}, {@code "SMS"})
     * @return the first matching template version, or empty when no such version exists
     */
    public static Optional<MessageTemplateVersion> resolve(
            MessageTemplateCatalog catalog, String channel) {
        if (catalog == null) {
            return Optional.empty();
        }
        for (MessageTemplateVersion version : catalog.versions()) {
            if (version.enabled() && version.runtimeAvailable()
                    && channel.equals(version.channel())) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}