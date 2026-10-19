package com.company.campaign.segment.reference;

import java.util.List;

/**
 * Host-only, read-only directory that turns untrusted audience wording into bounded, safe candidates.
 *
 * <p>This is the one boundary a model's natural-language audience name or identifier may cross. It answers
 * three read-only questions and nothing else:
 *
 * <ol>
 *   <li>which audiences may this caller reference whose safe label or exact identity matches one bounded
 *       candidate text?</li>
 *   <li>may this caller still reference one already-discovered audience?</li>
 *   <li>which immutable, published versions of one already-discovered audience are eligible?</li>
 * </ol>
 *
 * <p>Deliberately absent: any lookup by a mutable pointer, any method that answers "the current audience",
 * any resolve that trusts a caller-supplied version pin, and any write. There is no Save, Apply, Publish,
 * Retain or Delete operation of any kind, so a model cannot reach one through this port even by naming it.
 *
 * <p><strong>Non-disclosure.</strong> The first question returns only audiences the caller may reference, and
 * the second answers {@code false} exactly when the caller may no longer reference one it previously could.
 * A foreign tenant's or forbidden program's audience is therefore never distinguishable from one that does not
 * exist: a denied candidate does not appear in discovery at all. Because of that, a denied <em>discovery</em>
 * is one bounded "no candidate" outcome, while an audience that discovery already surfaced and that the
 * directory then refuses produces a denial — the caller is never told anything it could not already see.
 *
 * <p>Implementations must be safe for concurrent use, must never mutate published material and must return
 * bounded results: the accepted bounds are {@link ReferenceDiscoveryLimits#MAX_CANDIDATES} summaries per
 * candidate text and {@link ReferenceDiscoveryLimits#MAX_PUBLISHED_VERSIONS} versions per audience, and the
 * resolver refuses an over-bounded answer rather than truncating it into a different meaning.
 */
public interface AudienceReferenceDirectory {

    /** The audience summaries this caller may reference that match one bounded candidate text. */
    List<AudienceSummary> discover(String candidateText);

    /** True when this caller may still reference one audience an earlier discovery surfaced. */
    boolean accessible(String audienceId);

    /** The eligible immutable published versions of one already-discovered audience, bounded. */
    List<AudienceVersionChoice> publishedVersions(String audienceId);
}