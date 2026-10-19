package com.company.campaign.builder.campaignagent;

/**
 * The closed operation one turn's channel facts apply to the accepted communication channels.
 *
 * <p>A communication channel set is corrected, not only accreted: a user may add a channel, restate the whole
 * set so that it <em>replaces</em> the accepted one, or explicitly remove a channel they no longer want. Union
 * alone cannot express the last two, so the operation is an explicit server-owned vocabulary and the extractor
 * must name one. The enum carries no product wording, so a decoder accepts only the exact wire name.
 */
public enum ChannelOperation {

    /** Add the named channels to the accepted set, leaving every other accepted channel in place. */
    ADD("add the named channels to the accepted channel set"),

    /** Replace the accepted channel set with exactly the named channels. */
    REPLACE("replace the accepted channel set with exactly the named channels"),

    /** Remove the named channels from the accepted set, leaving every other accepted channel in place. */
    REMOVE("remove the named channels from the accepted channel set");

    private final String description;

    ChannelOperation(String description) {
        this.description = description;
    }

    /** The bounded product definition of this operation. */
    public String description() {
        return description;
    }

    /**
     * The exact enum name, matched case-sensitively; an unknown value and a differently-cased value both return
     * {@code null}, so a caller can refuse a non-canonical wire value rather than default an operation.
     */
    public static ChannelOperation fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (ChannelOperation operation : values()) {
            if (operation.name().equals(name)) {
                return operation;
            }
        }
        return null;
    }
}