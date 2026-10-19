package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.LocaleTag;

/**
 * Bounded client presentation context: locale only.
 *
 * <p>Presentation context affects how a result is shown, never what is executed. It therefore carries no
 * trusted execution field — no tenant, program, user, membership, DataScope, permission, revision, status,
 * timestamp or provider fact — and a reserved name anywhere inside it is refused by the codec. The record is
 * always present in the model; an absent {@code locale} is represented as null and is omitted from the
 * canonical form.
 */
public record ProposalClientContext(LocaleTag locale) {

    /** The one empty presentation context. */
    public static final ProposalClientContext EMPTY = new ProposalClientContext(null);

    @Override
    public String toString() {
        return "ProposalClientContext[locale=" + (locale == null ? "-" : locale.value()) + ']';
    }
}