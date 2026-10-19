package com.company.campaign.runtime;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;

import java.util.Optional;

/**
 * The narrow host port through which Runtime resolves the exact frozen definitions its work is bound to.
 *
 * <p>Two lookups, and only two: the immutable full publication a work item identifies, and the immutable base
 * published definition that full publication binds by published identifier. Nothing here resolves a mutable
 * latest pointer, a Draft, a Plan, a Proposal, a ChangeSet, a Prompt, a Skill, a Tool, an Agent or an LLM, and
 * nothing here re-resolves authoring Metadata or Catalog state.
 *
 * <p>A caller-supplied identifier is a lookup key, never an authority: the consumer verifies the returned
 * definition's own content hash against the hash its work item pinned before it trusts a single field.
 */
public interface RuntimeDefinitionLookupPort {

    /** The immutable full publication with the given identifier, or empty when the host holds none. */
    Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId);

    /** The immutable base published definition with the given published identifier, or empty. */
    Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId);
}