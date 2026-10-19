package com.company.campaign.conversation;

import com.company.campaign.conversation.postgres.ConversationPostgresProperties;
import com.company.campaign.readiness.postgres.ReadinessPostgresProperties;
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
 * T34B: the durable conversation boundary is a closed, canonical, non-reflective storage boundary.
 *
 * <p>The scan proves four things about the packages this task adds, at source level, because none of them can be
 * proved by a unit test on a value: no Java serialization and no Jackson default typing (a stored row is one
 * explicit canonical document, never a class name); no reflection and no private-field mutation (a session is
 * rebuilt through its accepted constructors); no mutable static state (the store is an instance with an injected
 * clock and bounds); and no string-built or unbounded SQL, no `SELECT *`, and no accidental shared migration
 * location between the readiness slice and the Campaign conversation slice.
 */
class ConversationStorageArchitectureTest {

    private static final Path CONVERSATION =
            Path.of("src", "main", "java", "com", "company", "campaign", "conversation");
    private static final Path POSTGRES = CONVERSATION.resolve("postgres");
    private static final Path READINESS =
            Path.of("src", "main", "java", "com", "company", "campaign", "readiness", "postgres");
    private static final Path MIGRATIONS =
            Path.of("src", "main", "resources", "db", "campaign-conversation-migration");
    private static final Path CONVERSATION_MAPPER =
            Path.of("src", "main", "resources", "mapper", "CampaignConversationMapper.xml");

    private static final List<String> FORBIDDEN_SERIALIZATION = List.of(
            "Serializable", "ObjectOutputStream", "ObjectInputStream", "writeObject(", "readObject(",
            "activateDefaultTyping", "enableDefaultTyping", "DefaultTyping", "JsonTypeInfo",
            "@JsonSubTypes", "PolymorphicTypeValidator", "java.io.");

    private static final List<String> FORBIDDEN_REFLECTION = List.of(
            "Class.forName", "getDeclaredField", "getDeclaredMethod", "getDeclaredFields",
            "setAccessible", "Method.invoke", "sun.misc.Unsafe", "MethodHandles");

    private static final List<String> FORBIDDEN_STATIC_STATE = List.of(
            "static final Map<", "static final HashMap<");

    private static final List<String> FORBIDDEN_SQL = List.of(
            "SELECT *", "SELECT COUNT(*)", "createStatement", "\"SELECT \" +", "String.format(\"SELECT");

    /** A non-final static <em>field</em>; the trailing {@code =} is what keeps static methods out of the match. */
    private static final Pattern NON_FINAL_STATIC = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private)?\\s*static\\s+(?!final\\b)[\\w<>,\\[\\]\\. ]+\\s+\\w+\\s*=");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theStorageBoundaryCarriesNoSerializationAndNoPolymorphicTyping() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CONVERSATION)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_SERIALIZATION) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("a stored conversation document is one explicit canonical value").isEmpty();
    }

    @Test
    void theStorageBoundaryUsesNoReflectionAndMutatesNoPrivateField() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CONVERSATION)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_REFLECTION) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).as("a session is rebuilt through its accepted constructors").isEmpty();
    }

    @Test
    void theStorageBoundaryHoldsNoMutableStaticState() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CONVERSATION)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_STATIC_STATE) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " -> " + forbidden);
                }
            }
            Matcher matcher = NON_FINAL_STATIC.matcher(source);
            if (matcher.find()) {
                violations.add(file.getFileName() + " -> non-final static state");
            }
        }
        assertThat(violations).as("the store is an instance with an injected clock and bounds").isEmpty();
    }

    @Test
    void theMyBatisStoreIssuesNoUnboundedOrStringBuiltSql() {
        String store = read(CONVERSATION.resolve("MyBatisPlusCampaignConversationStore.java"));
        String mapper = read(CONVERSATION_MAPPER).toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : FORBIDDEN_SQL) {
            assertThat(store).as("no unbounded or dynamically built statement").doesNotContain(forbidden);
        }
        assertThat(mapper)
                .contains("for update")
                .contains("and revision = #{expectedrevision}")
                .doesNotContain("select *");
        assertThat(store)
                .contains("CLEANUP_BATCH_LIMIT")
                .doesNotContain("Instant.now")
                .doesNotContain("System.currentTimeMillis")
                .doesNotContain("Math.random")
                .doesNotContain("new Random(")
                .doesNotContain("Thread");
    }

    @Test
    void thePortPackageStaysAPortPackageAndTheAdapterKeepsItsDependencies() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CONVERSATION)) {
            if (file.startsWith(POSTGRES)) {
                continue;
            }
            String source = read(file);
            if (source.contains("org.springframework.") || source.contains("java.net.")) {
                violations.add(file.toString());
            }
        }
        assertThat(violations).as("the conversation port package stays framework-free").isEmpty();
    }

    @Test
    void readinessAndConversationShareTheExplicitBusinessSessionFactory() {
        String readiness = read(READINESS.resolve("ReadinessPostgresConfiguration.java"));
        assertThat(readiness)
                .contains("CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME")
                .contains("CampaignDatasourceProperties.DATASOURCE_BEAN_NAME")
                .contains("campaignBusinessFlywayInitializer")
                .doesNotContain("new HikariDataSource");

        String conversation = read(POSTGRES.resolve("ConversationPostgresConfiguration.java"));
        assertThat(conversation)
                .contains("CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME")
                .doesNotContain("HikariDataSource")
                .doesNotContain("Flyway.configure");
    }

    @Test
    void theCampaignMigrationsAreADedicatedAssetSetSeparateFromReadiness() {
        assertThat(ConversationPostgresProperties.MIGRATION_LOCATIONS)
                .isNotEqualTo(ReadinessPostgresProperties.MIGRATION_LOCATIONS);
        assertThat(ConversationPostgresProperties.MIGRATION_LOCATIONS)
                .startsWith("classpath:db/campaign-conversation-migration");
        assertThat(ConversationPostgresProperties.RESERVED_SCHEMAS)
                .containsExactlyInAnyOrderElementsOf(ReadinessPostgresProperties.RESERVED_SCHEMAS);

        List<String> assets = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.filter(Files::isRegularFile).forEach(path -> assets.add(path.getFileName().toString()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        assertThat(assets).containsExactly(
                "V1__campaign_conversation_store.sql",
                "V2__campaign_business_resource_catalog.sql");
    }

    @Test
    void theAdapterImportsOnlyTheBoundedHostBoundaries() {
        for (Path file : javaFiles(POSTGRES)) {
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                assertThat(imported)
                        .as("import of " + imported + " in " + file.getFileName())
                        .matches("^(java\\.|javax\\.|jakarta\\.|com\\.company\\.campaign\\.|"
                                + "org\\.springframework\\.|org\\.apache\\.ibatis\\.).*");
            }
        }
    }

    // ------------------------------------------------------------------ scanning helpers

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
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
