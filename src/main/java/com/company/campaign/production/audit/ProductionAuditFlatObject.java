package com.company.campaign.production.audit;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hand-written builder of one flat canonical audit object.
 *
 * <p>It is used for exactly two things: an adapter's canonical source document, and an adapter's identity
 * material. Both are flat, bounded and explicit — an adapter declares every field it writes, one call at a time,
 * and there is no nested object, no array and no free-form payload anywhere.
 *
 * <p>For a source document, {@link #build(ProductionAuditEventType)} proves the object carries <em>exactly</em>
 * the field set its type declares: a missing field and an undeclared field are both refused, so a source field
 * cannot be silently dropped from the durable trail and nothing outside the closed vocabulary can be added.
 *
 * <p>Identifiers are never written here directly: an adapter passes a {@link ContentHash} for every trusted
 * identifier, and the only plain strings it may write are closed enum names, bounded machine codes, contract
 * versions, topics and hashes.
 */
final class ProductionAuditFlatObject {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectNode node = NODES.objectNode();

    private ProductionAuditFlatObject() {
    }

    /** A new, empty flat object. */
    static ProductionAuditFlatObject of() {
        return new ProductionAuditFlatObject();
    }

    /** Writes one bounded text field, or an explicit JSON null when the source carries no value. */
    ProductionAuditFlatObject text(String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
        return this;
    }

    /** Writes one closed enum constant name; an enum name is a bounded uppercase machine code. */
    ProductionAuditFlatObject name(String field, Enum<?> value) {
        return text(field, value == null ? null : value.name());
    }

    /** Writes one canonical content hash, or an explicit JSON null when the source carries no hash. */
    ProductionAuditFlatObject hash(String field, ContentHash value) {
        return text(field, value == null ? null : value.value());
    }

    /** Writes one bounded integral field, or an explicit JSON null when the source carries no value. */
    ProductionAuditFlatObject number(String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.longValue());
        }
        return this;
    }

    /** Writes one bounded boolean fact. */
    ProductionAuditFlatObject flag(String field, boolean value) {
        node.put(field, value);
        return this;
    }

    /** The flat object as written so far; used as identity material, which declares no field set of its own. */
    ObjectNode node() {
        return node;
    }

    /** Canonicalizes the object and proves it carries exactly the field set of the given type. */
    String build(ProductionAuditEventType type) {
        return ProductionAuditCodec.canonicalSourceDocument(type, node);
    }
}