package com.company.campaign.metadata.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.company.campaign.metadata.MetadataTexts;

import java.util.List;

/**
 * One value of an enum-typed attribute.
 *
 * <p>Codes are uppercase machine codes and unique; aliases keep their display text and must not collide
 * after normalization. An enum definition without values is rejected by the owning attribute.
 */
public record EnumValueDefinition(
        String code,
        List<String> aliases,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer rank)
        implements Comparable<EnumValueDefinition> {

    public EnumValueDefinition(String code, List<String> aliases) {
        this(code, aliases, null);
    }

    public EnumValueDefinition {
        code = MetadataTexts.requireMachineCode(code, "enumCode");
        aliases = MetadataTexts.canonicalTextList(aliases, "enumAlias");
    }

    @Override
    public int compareTo(EnumValueDefinition other) {
        return code.compareTo(other.code);
    }

    /** The normalized keys of this value's aliases, used for snapshot-wide collision checks. */
    public List<String> normalizedAliasKeys() {
        return aliases.stream().map(alias -> MetadataTexts.normalizedKey(alias, "enumAlias")).toList();
    }
}
