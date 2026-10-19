package com.company.campaign.dev.preview.postgres;

import java.util.Locale;
import java.util.Set;

/**
 * The execution-boundary re-proof of one server-owned compiled SQL artifact.
 *
 * <p>The accepted T09B compiler is the primary proof that an artifact is one read-only {@code SELECT}: the
 * artifact is non-forgeable, its text is re-rendered and re-hashed at construction, and every value is a bind
 * parameter. This class is the adapter's independent, fail-closed second proof. It refuses a statement that is
 * not exactly one {@code SELECT}, that carries a statement separator or a comment marker, or that contains a
 * DDL/DML/mutation keyword outside a quoted identifier or literal — so even a hypothetical future compiler
 * regression cannot turn the Preview boundary into a writer.
 *
 * <p>It is deliberately a closed scanner rather than a {@code contains} keyword check: double-quoted catalog
 * identifiers and single-quoted literals are skipped, and only words outside them are classified, so a table
 * or column legitimately named {@code update_log} is never mistaken for a mutation. A word the renderer cannot
 * emit is refused, and the very first word must be {@code SELECT}.
 */
final class PreviewSqlBoundary {

    /** The one statement shape the adapter may execute. */
    private static final String SELECT = "SELECT";

    /**
     * Every word the reader refuses outside a quoted region.
     *
     * <p>They cover the whole DDL/DML/session/multi-statement surface a single read-only {@code SELECT} never
     * needs, so a statement that reaches any of them is refused before it is prepared.
     */
    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "TRUNCATE", "DROP", "ALTER", "CREATE",
            "GRANT", "REVOKE", "COMMENT", "CALL", "DO", "COPY", "VACUUM", "ANALYZE", "REINDEX", "CLUSTER",
            "REFRESH", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "BEGIN", "START", "SET", "RESET", "SHOW",
            "LOCK", "EXECUTE", "PREPARE", "DEALLOCATE", "DISCARD", "LISTEN", "NOTIFY", "UNLISTEN", "EXPLAIN",
            "WITH", "INTO", "RETURNING", "VALUES", "SEQUENCE", "FUNCTION", "PROCEDURE", "TRIGGER", "DATABASE",
            "SCHEMA", "ROLE", "USER", "TABLESPACE", "INDEX", "VIEW", "POLICY", "RULE", "EXTENSION");

    private PreviewSqlBoundary() {
    }

    /**
     * Proves one compiled statement is exactly one read-only PostgreSQL {@code SELECT}.
     *
     * @return true when the statement is acceptable; the caller converts false into its one bounded failure
     */
    static boolean isSingleReadOnlySelect(String sql) {
        if (sql == null || sql.isEmpty() || !sql.startsWith(SELECT + ' ')) {
            return false;
        }
        // One statement only: a separator, a comment opener or a comment closer can never appear in the
        // deterministic renderer output, so any occurrence is a boundary violation and not a formatting detail.
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) {
            return false;
        }
        return scansClean(sql);
    }

    /** Walks the text once, skipping quoted regions and refusing any forbidden word outside them. */
    private static boolean scansClean(String sql) {
        String firstWord = null;
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char character = sql.charAt(index);
            if (character == '"' || character == '\'') {
                index = skipQuoted(sql, index, character);
                if (index < 0) {
                    return false;
                }
                continue;
            }
            if (isWordStart(character)) {
                int start = index;
                while (index < length && isWordPart(sql.charAt(index))) {
                    index++;
                }
                String word = sql.substring(start, index).toUpperCase(Locale.ROOT);
                if (firstWord == null) {
                    firstWord = word;
                }
                if (FORBIDDEN_WORDS.contains(word)) {
                    return false;
                }
                continue;
            }
            index++;
        }
        return SELECT.equals(firstWord);
    }

    /**
     * Advances past one quoted region and returns the index after its closing quote, or {@code -1} when it is
     * unterminated. A doubled quote inside a single-quoted literal is a literal quote, not a terminator.
     */
    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        int length = sql.length();
        while (index < length) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < length && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    private static boolean isWordStart(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || character == '_';
    }

    private static boolean isWordPart(char character) {
        return isWordStart(character) || (character >= '0' && character <= '9') || character == '$';
    }
}