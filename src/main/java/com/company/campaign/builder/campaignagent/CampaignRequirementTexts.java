package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The shared normalization of the bounded text and channel facts of a Campaign requirement brief.
 *
 * <p>Two rules are applied identically to a brief and to a delta, so the two value types can never disagree:
 * a bounded text fact is {@code null} when it was not supplied and is length-checked otherwise without being
 * rewritten (an exact user-authored message is never trimmed, translated or paraphrased), and a channel list
 * is reduced to its canonical, unique, ordered form derived from the closed vocabulary rather than from any
 * product wording.
 */
final class CampaignRequirementTexts {

    private CampaignRequirementTexts() {
    }

    /** {@code null}/empty stays absent; anything longer than the bound is refused, never truncated. */
    static String boundedText(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("A Campaign requirement field exceeds its bound: " + field);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * The canonical channel list: every element declared, no duplicate, ordered by the vocabulary's own
     * declaration order. A duplicate or an absent element is refused, so a non-canonical list cannot exist.
     */
    static List<CampaignMessageInputs.Channel> canonicalChannels(List<CampaignMessageInputs.Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        List<CampaignMessageInputs.Channel> ordered = new ArrayList<>(channels.size());
        for (CampaignMessageInputs.Channel channel : channels) {
            if (channel == null) {
                throw new IllegalArgumentException("A Campaign requirement channel must be declared.");
            }
            ordered.add(channel);
        }
        ordered.sort(Comparator.comparingInt(CampaignMessageInputs.Channel::ordinal));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1) == ordered.get(index)) {
                throw new IllegalArgumentException("A Campaign requirement channel list must be unique.");
            }
        }
        return List.copyOf(ordered);
    }

    /** The canonical union of two channel lists: every channel of either, unique and vocabulary-ordered. */
    static List<CampaignMessageInputs.Channel> unionChannels(
            List<CampaignMessageInputs.Channel> left, List<CampaignMessageInputs.Channel> right) {
        List<CampaignMessageInputs.Channel> union = new ArrayList<>(canonicalChannels(left));
        for (CampaignMessageInputs.Channel channel : canonicalChannels(right)) {
            if (!union.contains(channel)) {
                union.add(channel);
            }
        }
        return canonicalChannels(union);
    }

    /** The canonical base list with the named channels removed, leaving every other channel in place. */
    static List<CampaignMessageInputs.Channel> removeChannels(
            List<CampaignMessageInputs.Channel> base, List<CampaignMessageInputs.Channel> named) {
        List<CampaignMessageInputs.Channel> removed = new ArrayList<>(canonicalChannels(base));
        removed.removeAll(canonicalChannels(named));
        return List.copyOf(removed);
    }
}