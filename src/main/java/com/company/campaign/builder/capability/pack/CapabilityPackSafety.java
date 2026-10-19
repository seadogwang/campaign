package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The closed vocabulary of names a capability pack contract may never declare.
 *
 * <p>A pack contract is frozen, machine-readable metadata: it names fields, enumeration codes, reference
 * kinds and evaluation fixtures, and nothing else. It must never smuggle executable content, a provider
 * credential, a provider/endpoint fact, trusted tenant/program/user/DataScope authority, a mutable registry
 * handle or prompt text into that metadata — none of those can be represented by a value merely because a
 * name carries one.
 *
 * <p>Matching is deliberately loose and works on <em>words</em>: a name is split on separators and on
 * camel-case boundaries, lower-cased, and every resulting word is compared against a closed forbidden set.
 * A small supplementary set of compound tokens is matched against the whole normalized name so a form such
 * as {@code connectionString} or {@code apiKey} is refused even though neither of its words is forbidden on
 * its own. Rejecting a legitimate name is the safe direction — a pack author renames a field, whereas a
 * missed alias is an executable-content or truth-leak hole.
 *
 * <p>The scanner is capability-agnostic: it holds a generic safety vocabulary, no capability code, slot,
 * bound or business constant, and it reads no file, clock or registry.
 */
final class CapabilityPackSafety {

    /** Words that may never appear as one component of a declared name. */
    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            // Credentials and secrets.
            "credential", "credentials", "secret", "secrets", "password", "passphrase",
            "token", "tokens", "bearer", "oauth",
            // Executable content.
            "script", "scripts", "eval", "evaluate", "exec", "execute", "executable",
            "shell", "cmd", "command", "commands", "callback", "callbacks", "webhook", "webhooks",
            "hook", "hooks", "method", "methods", "reflect", "reflection", "invoke", "invocation",
            "instantiate", "groovy", "javascript", "beanshell", "mvel", "velocity", "freemarker",
            "thymeleaf", "jndi", "ldap", "rmi", "ognl", "spel", "class", "classname",
            // Classloading and process escape.
            "classloader", "forname", "processbuilder",
            // Storage, query and connection internals.
            "sql", "jdbc", "datasource", "dsn", "dialect", "query", "table", "tables",
            "column", "columns", "schemaname", "rowid", "primarykey", "foreignkey",
            "connection", "connectionstring", "connectionpool",
            // Arbitrary expressions.
            "expression", "expressions", "expr", "formula", "formulas",
            // Providers and endpoints.
            "provider", "providers", "vendor", "connector", "endpoint", "endpoints",
            "url", "uri", "hostname", "socket",
            // Trusted authority.
            "tenant", "tenants", "tenantid", "program", "programs", "programid",
            "user", "users", "userid", "member", "members", "memberid", "membership",
            "datascope", "permission", "permissions", "role", "roles", "authority", "authorities",
            "acl", "privilege", "privileges", "authorization", "authentication", "principal",
            // Mutable handles.
            "registry", "handle", "pointer", "mutable",
            // Prompt text.
            "prompt", "prompts", "instruction", "instructions");

    /** Compound tokens matched against the whole normalized name. */
    private static final Set<String> FORBIDDEN_COMPOUNDS = Set.of(
            "apikey", "accesskey", "privatekey", "secretkey", "keystore",
            "connectionstring", "connectionpool", "datascope", "securitycontext",
            "classloader", "processbuilder", "classforname", "newinstance",
            "systemprompt", "prompttext",
            "providercredential", "providercredentials", "providerconfig", "providerendpoint", "providerid",
            "registryhandle", "cataloghandle",
            "authorizationdecision", "authorizationreason", "authorizationheader");

    private CapabilityPackSafety() {
    }

    /** Refuses one name that carries executable, credential, provider or trusted-authority content. */
    static void requireSafe(String name, String invariant) {
        if (forbids(name)) {
            throw invalid(invariant);
        }
    }

    /** True when a declared name carries forbidden content; the offending word is never returned. */
    static boolean forbids(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = normalize(name);
        for (String compound : FORBIDDEN_COMPOUNDS) {
            if (normalized.contains(compound)) {
                return true;
            }
        }
        for (String word : words(name)) {
            if (FORBIDDEN_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The stable failure for one closed, bounded, deterministic pack contract invariant. */
    static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                invariant,
                "A capability pack contract must be a bounded, closed, deterministic declaration.");
    }

    /** Lower-cased, alphanumeric-only form of a name. */
    private static String normalize(String name) {
        StringBuilder normalized = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    /**
     * The lower-cased words of a name, split on separators and on camel-case boundaries.
     *
     * <p>The boundary test reads the <em>original</em> previous character, so a lower-to-upper transition
     * splits {@code referenceCode} while an all-uppercase token such as {@code SCRIPT} stays one word rather
     * than being shredded at every letter.
     */
    private static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char previous = 0;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                flush(words, current);
                previous = 0;
                continue;
            }
            boolean startsWord = current.length() > 0
                    && Character.isUpperCase(character)
                    && Character.isLowerCase(previous);
            if (startsWord) {
                flush(words, current);
            }
            current.append(Character.toLowerCase(character));
            previous = character;
        }
        flush(words, current);
        return words;
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }
    }
}