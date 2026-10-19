package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignMessageInputs.Channel;

import java.util.List;

/**
 * The coherent channel correction one turn supplies for the accepted communication channels.
 *
 * <p>It is a closed pair of one {@link ChannelOperation} and the canonical channels it names, so a turn can
 * express an addition, an explicit replacement of the whole accepted set, or an explicit removal of named
 * channels. The named list is canonical, unique and ordered by the closed channel vocabulary, and it is never
 * empty: an operation with nothing to apply is not a correction and cannot be constructed.
 */
public record CampaignChannelUpdate(ChannelOperation operation, List<Channel> channels) {

    public CampaignChannelUpdate {
        if (operation == null) {
            throw new IllegalArgumentException("A Campaign channel update requires one declared operation.");
        }
        channels = CampaignRequirementTexts.canonicalChannels(channels);
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("A Campaign channel update requires at least one channel.");
        }
    }

    /** The canonical channel set of one operated update over an accepted channel set. */
    public List<Channel> apply(List<Channel> accepted) {
        List<Channel> base = CampaignRequirementTexts.canonicalChannels(accepted);
        return switch (operation) {
            case ADD -> CampaignRequirementTexts.unionChannels(base, channels);
            case REPLACE -> channels;
            case REMOVE -> CampaignRequirementTexts.removeChannels(base, channels);
        };
    }
}