package com.company.campaign.production.provider;

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
 * T35C1: the production Provider package is architecture-scanned for the properties the task requires and the
 * implementation must keep.
 *
 * <p>It is a source scan and not a behavioural proof. It exists so a future change that reintroduces a Builder,
 * model, Agent or development import, an ambient clock, randomness, a direct process/environment read, a vendor
 * SDK, reflection or a second wait site fails loudly.
 */
class ProductionProviderArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "production", "provider");

    private static final Path TRANSPORT = PACKAGE.resolve("ProductionProviderTransport.java");

    private static final Path CONFIGURATION = PACKAGE.resolve("ProductionProviderConfiguration.java");

    private static final Path PROPERTIES = PACKAGE.resolve("ProductionProviderProperties.java");

    private static final Path MESSAGE_ADAPTER = PACKAGE.resolve("ProductionProviderMessageAdapter.java");

    private static final Path REWARD_ADAPTER = PACKAGE.resolve("ProductionProviderRewardAdapter.java");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.builder.",
            "com.company.campaign.dev.",
            "com.company.campaign.model.",
            "com.company.campaign.production.model.",
            "com.anthropic",
            "com.openai",
            "org.springframework.web.client",
            "org.apache.http",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.databind.jsontype",
            "java.lang.reflect");

    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "Instant.now(",
            "LocalDateTime.now(",
            "ZonedDateTime.now(",
            "Clock.system",
            "System.currentTimeMillis",
            "System.nanoTime",
            "new Random",
            "Math.random",
            "UUID.randomUUID",
            "Thread.sleep",
            "System.getenv",
            "System.getProperties",
            "System.out",
            "System.err",
            "Class.forName",
            "setAccessible");

    @Test
    void thePackageDependsOnNoBuilderModelAgentDevelopmentTypeVendorSdkOrReflection() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file.getFileName() + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations)
                .as("no Builder, model, Agent, development, vendor SDK or reflection import")
                .isEmpty();
    }

    @Test
    void theAdaptersAndTransportHoldNoAmbientClockRandomnessSleepOrProcessRead() {
        for (Path file : List.of(TRANSPORT, MESSAGE_ADAPTER, REWARD_ADAPTER)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CONSTRUCTS) {
                assertThat(source)
                        .as(file.getFileName() + " must not contain " + forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void theTransportDisablesRedirectsAndBoundsAndStrictlyDecodesEveryResponse() {
        String source = read(TRANSPORT);
        assertThat(source)
                .contains("HttpClient.Redirect.NEVER")
                .contains("readNBytes(")
                .contains("ProductionProviderWireContract.KEY_HEADER")
                .contains("ProductionProviderWireContract.assertHeaderMatchesBody")
                .contains("new ActionProviderException");
        // A submission is never repeated inside the adapter: only the query path carries a retry loop.
        assertThat(source).contains("getQueryMaxAttempts()");
    }

    @Test
    void theWireContractProvesHeaderBodyEqualityAndStrictlyDecodesEveryResponse() {
        String source = read(PACKAGE.resolve("ProductionProviderWireContract.java"));
        assertThat(source)
                .contains("ProductionProviderErrorCodes.PROVIDER_KEY_MISMATCH")
                .contains("RESPONSE_FIELDS.contains")
                .contains("assertHeaderMatchesBody")
                .contains("onMalformedInput(CodingErrorAction.REPORT)")
                .contains("UNSAFE_CONTROL");
    }

    @Test
    void onlyTheConfigurationBeanPerformsTheBoundedWait() {
        assertThat(read(CONFIGURATION)).contains("Thread.sleep(millis)");
        for (Path file : javaFiles()) {
            if (!file.equals(CONFIGURATION)) {
                assertThat(read(file)).as(file.getFileName().toString()).doesNotContain("Thread.sleep");
            }
        }
    }

    @Test
    void theAdaptersImplementTheExactAcceptedSpis() {
        assertThat(read(MESSAGE_ADAPTER))
                .contains("implements MessageProvider")
                .contains("ProviderResult send(ProviderRequest request, String idempotencyKey)")
                .contains("ProviderResult queryByKey(String idempotencyKey)")
                .contains("ProviderCapability capability(String tenantId)");
        assertThat(read(REWARD_ADAPTER))
                .contains("implements RewardProvider")
                .contains("ProviderResult grant(ProviderRequest request, String idempotencyKey)")
                .contains("ProviderResult queryByKey(String idempotencyKey)")
                .contains("ProviderCapability capability(String tenantId)");
    }

    @Test
    void theConfigurationNamesAndConditionsEveryBeanItPublishes() {
        String source = read(CONFIGURATION);
        assertThat(source)
                .contains("@Bean(name = MESSAGE_BEAN_NAME)")
                .contains("@Bean(name = REWARD_BEAN_NAME)")
                .contains("@Bean(name = MESSAGE_EVIDENCE_BEAN_NAME)")
                .contains("@Bean(name = REWARD_EVIDENCE_BEAN_NAME)")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production\", name = \"enabled\", "
                        + "havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.provider.message\", "
                        + "name = \"enabled\", havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.provider.reward\", "
                        + "name = \"enabled\", havingValue = \"true\")");
    }

    @Test
    void noCommittedProductionProviderConfigurationCarriesAValue() {
        String source = read(PROPERTIES);
        assertThat(source)
                .contains("private boolean enabled = false;")
                .contains("private boolean requireHttps = true;")
                .contains("private String endpoint;")
                .contains("private String credential;")
                .contains("private String providerId;")
                .contains("private String ownerTenantId;")
                .doesNotContain("https://");
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
