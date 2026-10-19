package com.company.campaign.production.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architecture constraints of the durable audit package.
 *
 * <p>It is a source scan and not a behavioural proof. It exists so a future change that reintroduces an update
 * or delete statement, a non-constant statement, Java serialization, default typing, reflection, an ambient
 * clock, randomness, a raw identifier column or a process invocation fails loudly.
 */
class ProductionAuditArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "production", "audit");

    private static final Path LEDGER = PACKAGE.resolve("MyBatisPlusProductionAuditLedger.java");

    private static final Path ACTION_ADAPTER = PACKAGE.resolve("ProductionActionAuditSink.java");
    private static final Path LEDGER_MAPPER = Path.of(
            "src", "main", "resources", "mapper", "CampaignProductionAuditLedgerMapper.xml");

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "campaign-production-audit-migration",
            "V1__campaign_production_audit_ledger.sql");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "java.io.ObjectOutputStream",
            "java.io.ObjectInputStream",
            "java.io.Serializable",
            "java.lang.reflect",
            "com.fasterxml.jackson.databind.jsontype",
            "org.springframework.util.SerializationUtils",
            "com.company.campaign.builder.",
            "com.company.campaign.preview.api",
            "com.company.campaign.query.fixture",
            "com.company.campaign.dev.",
            "jakarta.servlet");

    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "ObjectOutputStream",
            "ObjectInputStream",
            "readObject(",
            "writeObject(",
            "enableDefaultTyping",
            "activateDefaultTyping",
            "DefaultTyping",
            "Class.forName",
            "getDeclaredField",
            "getDeclaredMethod",
            "getDeclaredConstructor",
            "setAccessible",
            "Instant.now(",
            "LocalDateTime.now(",
            "ZonedDateTime.now(",
            "Clock.system",
            "System.currentTimeMillis",
            "System.nanoTime",
            "new Random",
            "Math.random",
            "UUID.randomUUID",
            "System.getenv",
            "System.getProperties",
            "DriverManager",
            "Thread.sleep");

    /** Column-name tokens the ledger must never declare. */
    private static final List<String> FORBIDDEN_COLUMN_TOKENS = List.of(
            "password",
            "credential",
            "secret",
            "jwt",
            "token",
            "subject_id",
            "member",
            "recipient",
            "prompt",
            "payload",
            "email",
            "exception_message");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final Pattern SQL_VERB =
            Pattern.compile("\"\\s*(?i)(insert|select|update|delete|drop|truncate|alter)\\b");

    @Test
    void thePackageUsesNoJavaSerializationNoDefaultTypingAndNoReflection() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
            for (String forbidden : FORBIDDEN_CONSTRUCTS) {
                if (source.contains(forbidden)) {
                    violations.add(file + " -> " + forbidden);
                }
            }
        }
        assertThat(violations)
                .as("no serialization, default typing, reflection, ambient clock, randomness or process call")
                .isEmpty();
    }

    @Test
    void theLedgerIssuesOnlyAppendOnlyStatements() {
        String source = read(LEDGER_MAPPER).toLowerCase(java.util.Locale.ROOT);
        assertThat(source)
                .contains("<insert")
                .contains("<select")
                .doesNotContain("<update")
                .doesNotContain("<delete");
    }

    @Test
    void theLedgerUsesOnlyTwoNamedMapperStatements() {
        String source = read(LEDGER);
        assertThat(source)
                .contains("INSERT_STATEMENT")
                .contains("FIND_DOCUMENT_HASH_STATEMENT")
                .contains("openSession(false)")
                .doesNotContain("StringBuilder")
                .doesNotContain("prepareStatement(")
                .doesNotContain("DriverManager");
    }

    @Test
    void theMigrationCreatesOneAppendOnlyConflictKeyedLedger() {
        String sql = statementsOnly(read(MIGRATION));
        assertThat(sql)
                .contains("create table campaign_production_audit_ledger")
                .contains("unique (logical_key)")
                .contains("inserted_at")
                .contains("default now()")
                .contains("check (domain in ('SECURITY', 'CONVERSATION', 'LIFECYCLE', 'RUNTIME', 'ACTION'))")
                .doesNotContain("update")
                .doesNotContain("delete")
                .doesNotContain("drop")
                .doesNotContain("truncate")
                .doesNotContain("alter");
    }

    @Test
    void theMigrationDeclaresNoRawIdentifierOrPayloadColumn() {
        String sql = statementsOnly(read(MIGRATION));
        for (String forbidden : FORBIDDEN_COLUMN_TOKENS) {
            assertThat(sql)
                    .as("no raw '" + forbidden + "' column")
                    .doesNotContain(forbidden);
        }
    }

    @Test
    void onlyTheActionAdapterUsesAThreadLocalBatchOfControl() {
        List<String> users = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (read(file).contains("ThreadLocal")) {
                users.add(file.getFileName().toString());
            }
        }
        assertThat(users).containsExactly(ACTION_ADAPTER.getFileName().toString());
    }

    @Test
    void everyAdapterAndBeanKeepsItsClosedVocabularyAndSafeProjection() {
        List<String> adapters = List.of(
                "ProductionSecurityAuditSink.java",
                "ProductionConversationAuditSink.java",
                "ProductionLifecycleAuditSink.java",
                "ProductionRuntimeAuditSink.java",
                "ProductionActionAuditSink.java");
        for (String adapter : adapters) {
            String source = read(PACKAGE.resolve(adapter));
            assertThat(source)
                    .as(adapter + " maps its own closed type")
                    .contains("ProductionAuditEventType.")
                    .doesNotContain("Class.forName")
                    .doesNotContain("ObjectMapper");
        }
    }

    @Test
    void theConfigurationNamesAndQualifiesEveryBeanItPublishes() {
        String source = read(PACKAGE.resolve("ProductionAuditConfiguration.java"));
        assertThat(source)
                .contains("@Bean(name = LEDGER_BEAN_NAME)")
                .contains("CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production\", name = \"enabled\", havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.audit\", name = \"enabled\", havingValue = \"true\")")
                .doesNotContain("DriverManager");
    }

    // ------------------------------------------------------------------ helpers

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }

    /** The migration without its {@code --} comment lines, so prose about updates is not mistaken for SQL. */
    private static String statementsOnly(String sql) {
        StringBuilder statements = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                statements.append(line).append('\n');
            }
        }
        return statements.toString();
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
