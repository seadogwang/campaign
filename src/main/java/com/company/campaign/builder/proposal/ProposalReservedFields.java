package com.company.campaign.builder.proposal;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed set of names that may never appear inside a capability payload or a client presentation context.
 *
 * <p>A model or Visual Builder submits the wire request; the wire request is the only structure either may
 * produce. Trusted tenant, program, user, membership and DataScope values, permissions, authorization
 * decisions, revision overrides, lifecycle and published identifiers, plan/Manifest/ChangeSet artifacts,
 * storage identifiers, timestamps, credentials and Apply/Publish flags all belong to the server, so their
 * names are refused outright — at any nesting level, inside objects and inside array elements.
 *
 * <p>Matching is deliberately loose: a name is compared case-insensitively with every non-alphanumeric
 * character removed, so {@code tenantId}, {@code tenant_id}, {@code TENANT-ID} and {@code tenant.id} are all
 * refused. Rejecting a legitimate name is the safe direction — a capability author renames a field, whereas
 * a missed alias is a privilege-escalation or truth-leak hole.
 *
 * <p>Screening is applied to the free-form regions of the request — every capability payload and the client
 * presentation context. The structural fields of the wire format are governed by exact-field checks
 * elsewhere, so a reserved name cannot appear there either.
 */
public final class ProposalReservedFields {

    /** Normalized names that may never appear in a free-form proposal region. */
    private static final Set<String> RESERVED = Set.of(
            // Trusted ownership and identity.
            "tenant", "tenantid", "tenantname", "tenants", "tenantcode",
            "program", "programid", "programname", "programs", "programcode",
            "user", "userid", "username", "currentuser", "actor", "actorid",
            "principal", "principalid", "subject", "subjectid", "member", "memberid",
            "membership", "membershipid", "memberships", "campaignid", "campaign",
            "datascope", "datascopeid", "datascopes", "scope", "scopes", "visibility",
            // Authority.
            "permission", "permissions", "role", "roles", "authority", "authorities",
            "grant", "grants", "entitlement", "entitlements", "acl",
            // Authorization outcomes.
            "authorization", "authorizationdecision", "authorizationreason", "authorizeddecision",
            "decision", "decisions", "allowed", "denied", "authorized",
            // Revisions and lifecycle.
            "revision", "revisions", "revisionoverride", "baserevision", "baserevisions",
            "overrides", "override",
            "status", "state", "lifecycle", "published", "publishedid", "publishedversion",
            "publishstate", "publishedstatus", "publisheddefinition",
            // Compiler artifacts that are never inputs.
            "plan", "planid", "planhash", "manifest", "manifestid", "manifesthash",
            "resolvedmanifest", "resolvedcampaignmanifest", "changeset", "changesset", "semanticdiff",
            // Gate and readiness facts, which are always host-owned compile inputs.
            "readiness", "readinessstate", "readinessschema", "readinessreasons", "readinessreasoncodes",
            "capabilityreadiness", "activeready", "aiready",
            "gate", "gates", "gateinputs", "gateinputshash", "gateevidence", "gatepins", "gaterunpins",
            "gateapproval", "gateapprovals", "approveddecision", "approveddecisionhash", "gateoutcome",
            // Storage and query internals.
            "sql", "sqltext", "table", "tablename", "tables", "schema", "schemaname",
            "column", "columnname", "columns", "storagecatalog", "catalogtable",
            "rowid", "primarykey", "foreignkey", "jdbc", "datasource", "connectionstring",
            // Time and retention. The trusted time pins of a resolution are compile/context facts and are
            // never model/client Proposal fields, so their normalized aliases are reserved outright, under any
            // spelling: `asOf`, `as_of` and `AS-OF` all normalize to one refused name.
            "timestamp", "timestamps", "createdat", "updatedat", "compiledat", "expiresat",
            "expiry", "expireson", "expiration", "ttl", "validuntil",
            "asof", "timezone", "timezoneid", "datawatermark", "watermark",
            // Providers and secrets.
            "provider", "providerid", "providercredentials", "credentials", "credential",
            "secret", "secrets", "token", "tokens", "apikey", "password", "accesskey",
            // Host-only commands and flags.
            "apply", "applyflag", "applyenabled", "save", "savedraft", "publish", "publishflag",
            "pause", "resume", "retry", "archive", "reject", "confirm", "approve");

    private ProposalReservedFields() {
    }

    /**
     * True when the value contains a reserved name anywhere in its object/array tree.
     *
     * <p>The offending name is deliberately not returned: it is attacker-controlled input and must never
     * become observable output.
     */
    public static boolean containsReservedField(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isReserved(String.valueOf(entry.getKey()))) {
                    return true;
                }
                if (containsReservedField(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (containsReservedField(element)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterator<?> iterator) {
            while (iterator.hasNext()) {
                if (containsReservedField(iterator.next())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (containsReservedField(element)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** True when a single field name is reserved, under loose normalized matching. */
    public static boolean isReserved(String fieldName) {
        return fieldName != null && RESERVED.contains(normalize(fieldName));
    }

    private static String normalize(String fieldName) {
        StringBuilder normalized = new StringBuilder(fieldName.length());
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                // Locale-independent lowering: the reserved-name decision must not depend on the default locale.
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }
}