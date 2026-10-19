package com.company.campaign.builder.segment;

import com.company.campaign.metadata.domain.EventSelectorBinding;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.EventSelectorOrder;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetricFilterDimension;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The deterministic Metadata dependency closure of one Event Selector.
 *
 * <p>An Event Selector can only be described and executed when the exact definition-ID set admitted by
 * {@code metadata.discover} contains every Metadata ID the selector references:
 *
 * <ol>
 *   <li>every binding {@code relationId};</li>
 *   <li>every binding {@code identityAttributeId}, which locates the governed member/entity in the event
 *       stream and is therefore never optional even when it is not also an ordering Attribute;</li>
 *   <li>every {@code orderBy[].attributeId};</li>
 *   <li>every {@code fixedFilter.entries[].key}; these keys are Attribute IDs, exactly as
 *       {@code MetadataSnapshotValidator}, {@code SegmentQueryPlanner}, {@code SemanticQueryPlan} and
 *       {@code SqlCompiler} already treat them;</li>
 *   <li>every {@code scopeFilterDimensions[].attributeId}.</li>
 * </ol>
 *
 * <p>The closure is sorted, duplicate-free and immutable, so the same selector always yields the same bytes
 * and the rule can be audited in one place. Both the Agent and the request factory consult this single
 * predicate instead of each maintaining their own authorization logic.
 */
public final class EventSelectorDependencyClosure {

    private EventSelectorDependencyClosure() {
    }

    /** Every Metadata definition ID {@code selector} references, in deterministic natural order. */
    public static List<String> definitionIds(EventSelectorDefinition selector) {
        SortedSet<String> definitionIds = new TreeSet<>();
        for (EventSelectorBinding binding : selector.bindings()) {
            definitionIds.add(binding.relationId());
            definitionIds.add(binding.identityAttributeId());
            for (EventSelectorOrder order : binding.orderBy()) {
                definitionIds.add(order.attributeId());
            }
            for (MetadataFilterEntry entry : binding.fixedFilter().entries()) {
                definitionIds.add(entry.key());
            }
            for (MetricFilterDimension dimension : binding.scopeFilterDimensions()) {
                definitionIds.add(dimension.attributeId());
            }
        }
        return List.copyOf(definitionIds);
    }

    /**
     * True when the exact admitted definition-ID set contains the entire dependency closure of
     * {@code selector}. An unauthorized, absent or stale dependency denies the whole selector.
     *
     * <p>A {@code null} set is the diagnostic whole-snapshot behaviour of the development harness; the formal
     * Agent always supplies the exact set returned by the admitted {@code metadata.discover} Tool.
     */
    public static boolean isAuthorized(EventSelectorDefinition selector, Set<String> admittedDefinitionIds) {
        return admittedDefinitionIds == null || admittedDefinitionIds.containsAll(definitionIds(selector));
    }
}