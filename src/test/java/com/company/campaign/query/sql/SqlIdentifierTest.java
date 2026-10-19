package com.company.campaign.query.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1: catalog identifiers reject unsafe, mixed-case, oversized, quoted, comment and qualified
 * inputs, and render through one deterministic PostgreSQL double-quoted form.
 */
class SqlIdentifierTest {

    @Test
    void acceptsBoundedLowercaseSnakeCase() {
        assertThat(SqlIdentifier.of("members").value()).isEqualTo("members");
        assertThat(SqlIdentifier.of("member_id").value()).isEqualTo("member_id");
        assertThat(SqlIdentifier.of("a").value()).isEqualTo("a");
        assertThat(SqlIdentifier.of("a" + "0".repeat(62)).value()).hasSize(63);
        assertThat(SqlIdentifier.of("order_2024_q1").quoted()).isEqualTo("\"order_2024_q1\"");
    }

    @Test
    void rejectsEveryUnsafeOrMalformedIdentifier() {
        List<String> rejected = List.of(
                "Members",
                "MEMBER_ID",
                "Member_Id",
                "1member",
                "_member",
                "member-id",
                "member id",
                "member\"id",
                "member'id",
                "member;drop",
                "member--comment",
                "member/*comment*/",
                "public.members",
                "\"members\"",
                "members;",
                "members\n",
                "member id\t",
                "",
                "a" + "0".repeat(63));
        for (String candidate : rejected) {
            SqlException failure = SqlTestSupport.sqlFailure(() -> SqlIdentifier.of(candidate));
            assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_IDENTIFIER_INVALID);
            if (!candidate.isEmpty()) {
                assertThat(failure.getMessage()).doesNotContain(candidate);
            }
        }
    }

    @Test
    void rejectsNullWithTheStableIdentifierCode() {
        SqlException failure = SqlTestSupport.sqlFailure(() -> SqlIdentifier.of(null));
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_IDENTIFIER_INVALID);
    }

    @Test
    void quotesTheSameIdentifierDeterministically() {
        assertThat(SqlIdentifier.of("members").quoted()).isEqualTo(SqlIdentifier.of("members").quoted());
        assertThat(SqlIdentifier.of("members").quoted()).isEqualTo("\"members\"");
    }

    @Test
    void tableReferenceRequiresBothValidatedIdentifiers() {
        TableRef reference = new TableRef(SqlIdentifier.of("public"), SqlIdentifier.of("members"));
        assertThat(reference.render()).isEqualTo("\"public\".\"members\"");
        SqlException failure = SqlTestSupport.sqlFailure(
                () -> new TableRef(SqlIdentifier.of("public"), null));
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_CATALOG_INVALID);
    }
}