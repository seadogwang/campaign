package com.company.campaign.dev.preview.postgres;

import com.company.campaign.query.sql.CompiledSql;
import com.company.campaign.query.sql.SqlParameter;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.sql.SqlTestSupport;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34C requirement 3.1: every {@link SqlParameterType} binds through its exact JDBC call and object SQL type,
 * in the artifact's exact order, with no generic {@code setObject(value)} fallback anywhere.
 */
class PreviewJdbcBindingTest {

    @Test
    void everyParameterTypeBindsThroughItsExactJdbcCallInOrder() throws SQLException {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary();
        PreparedStatement statement = boundary.preparedStatement();

        PreviewJdbcBinding.bind(statement, List.of(
                SqlParameter.booleanValue(true),
                SqlParameter.integer(-42L),
                SqlParameter.decimal(new java.math.BigDecimal("12.50")),
                SqlParameter.text("a text value"),
                SqlParameter.enumCode("GOLD"),
                SqlParameter.date("2026-10-01"),
                SqlParameter.timestamp(java.time.Instant.parse("2026-10-01T02:00:00Z")),
                SqlParameter.currency("CNY"),
                SqlParameter.contextId("scope-008")));

        assertThat(boundary.bindings()).containsExactly(
                "setBoolean[1]=true",
                "setLong[2]=-42",
                "setBigDecimal[3]=12.5",
                "setString[4]=a text value",
                "setObject[5]=GOLD@OTHER",
                "setObject[6]=2026-10-01@DATE",
                "setObject[7]=2026-10-01T02:00Z@TIMESTAMP_WITH_TIMEZONE",
                "setObject[8]=CNY@CHAR",
                "setString[9]=scope-008");
    }

    @Test
    void everyDeclaredParameterTypeIsCoveredByTheMapping() {
        List<String> declared = new ArrayList<>();
        for (SqlParameterType type : SqlParameterType.values()) {
            declared.add(type.name());
        }
        assertThat(declared).containsExactly(
                "BOOLEAN", "INTEGER", "DECIMAL", "TEXT", "ENUM", "DATE", "TIMESTAMP", "CURRENCY", "CONTEXT_ID");

        // Every declared type has exactly one arm; a new type without an arm would not compile, and this
        // assertion keeps the closed vocabulary visible to a reader of the test.
        for (SqlParameterType type : SqlParameterType.values()) {
            assertThat(expectedCallName(type)).isNotBlank();
        }
    }

    @Test
    void theAdapterBindsTheCompiledArtifactsOwnParametersInTheirExactOrder() throws SQLException {
        StorageCatalog catalog = SqlTestSupport.standardCatalog();
        // No external FX binding is needed: this plan requires no currency conversion, so the compiler ignores it.
        CompiledSql compiled = SqlTestSupport.compile(
                SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")),
                catalog,
                null);

        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary();
        PreviewJdbcBinding.bind(boundary.preparedStatement(), compiled.parameters());

        List<String> expected = new ArrayList<>();
        int index = 1;
        for (SqlParameter parameter : compiled.parameters()) {
            expected.add(expectedCallName(parameter.type()) + "[" + index + "]");
            index++;
        }
        assertThat(compiled.parameters()).isNotEmpty();
        assertThat(boundary.bindings()).hasSameSizeAs(compiled.parameters());
        for (int position = 0; position < expected.size(); position++) {
            assertThat(boundary.bindings().get(position)).startsWith(expected.get(position));
        }
        assertThat(compiled.parameters()).contains(SqlParameter.enumCode("GOLD"));
    }

    /** The one JDBC call this adapter is allowed to use for one declared parameter type. */
    private static String expectedCallName(SqlParameterType type) {
        return switch (type) {
            case BOOLEAN -> "setBoolean";
            case INTEGER -> "setLong";
            case DECIMAL -> "setBigDecimal";
            case TEXT, CONTEXT_ID -> "setString";
            case ENUM, DATE, TIMESTAMP, CURRENCY -> "setObject";
        };
    }
}