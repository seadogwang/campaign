package com.company.campaign.query.sql;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable, hash-bound, non-forgeable compiled PostgreSQL artifact.
 *
 * <p>It carries the deterministic {@code SELECT} text, the ordered immutable typed bind parameters, the closed
 * SQL AST they came from, the source plan hash, the exact catalog version and content hash, the
 * compiler/AST/render-format versions, the optional external FX-binding hash and one canonical compiled hash
 * covering all of it.
 *
 * <p>It is a read boundary, not a container: it has no public constructor, no public static factory, no
 * builder, no deserializer and it cannot be subclassed, so an external caller cannot forge one. Construction
 * re-renders the AST and requires the text and parameter list to match exactly, then recomputes the compiled
 * hash and requires it to match the declared value; a caller can read but never mutate, because every
 * collection is defensively copied and every component is immutable.
 */
public final class CompiledSql {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final SqlSelect ast;
    private final String sql;
    private final List<SqlParameter> parameters;
    private final ContentHash planHash;
    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final String compilerVersion;
    private final String astVersion;
    private final String renderFormatVersion;
    private final ContentHash externalBindingHash;
    private final ContentHash compiledHash;

    /** The one construction path; package-private so only the compiler and its in-package tests can reach it. */
    CompiledSql(
            SqlSelect ast,
            String sql,
            List<SqlParameter> parameters,
            ContentHash planHash,
            String catalogVersion,
            ContentHash catalogHash,
            String compilerVersion,
            String astVersion,
            String renderFormatVersion,
            ContentHash externalBindingHash,
            ContentHash compiledHash) {
        this.ast = requirePresent(ast, "compiledAst");
        this.sql = requirePresent(sql, "compiledSql");
        if (parameters == null) {
            throw invalid("compiledParameters");
        }
        for (SqlParameter parameter : parameters) {
            requirePresent(parameter, "compiledParameters");
        }
        this.parameters = List.copyOf(parameters);
        this.planHash = requirePresent(planHash, "compiledPlanHash");
        this.catalogVersion = requireVersion(catalogVersion, "compiledCatalogVersion");
        this.catalogHash = requirePresent(catalogHash, "compiledCatalogHash");
        this.compilerVersion = requireVersion(compilerVersion, "compiledCompilerVersion");
        this.astVersion = requireVersion(astVersion, "compiledAstVersion");
        this.renderFormatVersion = requireVersion(renderFormatVersion, "compiledRenderFormatVersion");
        this.externalBindingHash = externalBindingHash;

        // Re-render the AST: the declared text and parameter order must be exactly what the AST produces.
        SqlRenderer.Rendered rendered = SqlRenderer.render(this.ast);
        if (!rendered.sql().equals(this.sql) || !rendered.parameters().equals(this.parameters)) {
            throw invalid("compiledCoherence");
        }

        ContentHash expected = computeHash(
                this.ast, this.sql, this.parameters, this.planHash, this.catalogVersion, this.catalogHash,
                this.compilerVersion, this.astVersion, this.renderFormatVersion, this.externalBindingHash);
        if (compiledHash == null || !compiledHash.equals(expected)) {
            throw invalid("compiledHash");
        }
        this.compiledHash = compiledHash;
    }

    /** The one canonical compiled-hash function, shared by the compiler and this construction boundary. */
    static ContentHash computeHash(
            SqlSelect ast,
            String sql,
            List<SqlParameter> parameters,
            ContentHash planHash,
            String catalogVersion,
            ContentHash catalogHash,
            String compilerVersion,
            String astVersion,
            String renderFormatVersion,
            ContentHash externalBindingHash) {
        return CANONICAL_JSON.hash(new Material(
                astVersion,
                renderFormatVersion,
                compilerVersion,
                SqlWire.select(ast),
                sql,
                parameters,
                planHash.value(),
                catalogVersion,
                catalogHash.value(),
                externalBindingHash == null ? null : externalBindingHash.value()));
    }

    /** The closed SQL AST this artifact was rendered from. */
    public SqlSelect ast() {
        return ast;
    }

    /** The one deterministic PostgreSQL SELECT statement; never contains a semicolon or a comment. */
    public String sql() {
        return sql;
    }

    /** The ordered immutable typed bind parameters, in exactly their {@code ?} marker order. */
    public List<SqlParameter> parameters() {
        return parameters;
    }

    /** The hash of the exact T08 plan this artifact was compiled from. */
    public ContentHash planHash() {
        return planHash;
    }

    /** The exact server-owned catalog version pinned into this artifact. */
    public String catalogVersion() {
        return catalogVersion;
    }

    /** The exact server-owned catalog content hash pinned into this artifact. */
    public ContentHash catalogHash() {
        return catalogHash;
    }

    /** The compiler version that produced this artifact. */
    public String compilerVersion() {
        return compilerVersion;
    }

    /** The SQL AST discriminator version pinned into this artifact. */
    public String astVersion() {
        return astVersion;
    }

    /** The PostgreSQL render-format version pinned into this artifact. */
    public String renderFormatVersion() {
        return renderFormatVersion;
    }

    /** The external FX-binding hash, or {@code null} when no conversion binding was supplied. */
    public ContentHash externalBindingHash() {
        return externalBindingHash;
    }

    /** The canonical compiled hash covering the AST, text, parameters, pins and versions. */
    public ContentHash compiledHash() {
        return compiledHash;
    }

    private static <T> T requirePresent(T value, String invariant) {
        if (value == null) {
            throw invalid(invariant);
        }
        return value;
    }

    private static String requireVersion(String value, String invariant) {
        if (value == null || value.isBlank()) {
            throw invalid(invariant);
        }
        return value;
    }

    private static SqlException invalid(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_ARTIFACT_INVALID, invariant,
                "A compiled SQL artifact violated its own coherence or hash invariant.");
    }

    private record Material(
            String astVersion,
            String renderFormatVersion,
            String compilerVersion,
            Map<String, Object> ast,
            String sql,
            List<SqlParameter> parameters,
            String planHash,
            String catalogVersion,
            String catalogHash,
            String externalBindingHash) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompiledSql compiled && Objects.equals(compiledHash, compiled.compiledHash);
    }

    @Override
    public int hashCode() {
        return compiledHash.hashCode();
    }

    @Override
    public String toString() {
        return "CompiledSql[catalogVersion=" + catalogVersion
                + ", parameterCount=" + parameters.size()
                + ", compiledHash=" + compiledHash + ']';
    }
}