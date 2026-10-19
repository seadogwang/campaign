package com.company.campaign.builder.assembly;

/**
 * The closed purpose of one Campaign Tool.
 *
 * <p>It exists so a descriptor declares what a Tool <em>is</em> in a machine-readable way, rather than only by
 * name. The set is deliberately small and every value is a read-only or compute-only purpose: there is no
 * mutation, persistence, publication or runtime purpose a descriptor could name, so a Tool can never describe
 * itself as an Apply, Publish, Save or activation step.
 */
public enum CampaignToolKind {

    /** Bounded discovery over a host-supplied immutable catalog. */
    DISCOVERY,

    /** Resolution of one reference to one exact immutable catalog version. */
    RESOLUTION,

    /** Validation of supplied values against an exact immutable catalog version. */
    VALIDATION,

    /** Deterministic calculation from explicit bounded inputs. */
    CALCULATION,

    /** Projection of a bounded, non-disclosing safe card. */
    PREVIEW;
}