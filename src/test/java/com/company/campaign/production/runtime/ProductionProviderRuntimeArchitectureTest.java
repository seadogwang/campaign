package com.company.campaign.production.runtime;

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
 * T35C2: the production provider runtime package is architecture-scanned for the properties the task requires.
 *
 * <p>It is a source scan, not a behavioural proof. It exists so a future change that reintroduces a Builder,
 * Agent, Skill, Prompt, model, development or simulated-provider import, an ambient clock, randomness, a sleep, a
 * direct process/environment read, a vendor SDK or reflection fails loudly.
 */
class ProductionProviderRuntimeArchitectureTest {

    private static final Path PACKAGE = Path.of(
            "src", "main", "java", "com", "company", "campaign", "production", "runtime");

    private static final Path CONFIGURATION =
            PACKAGE.resolve("ProductionProviderRuntimeConfiguration.java");

    private static final Path PROPERTIES = PACKAGE.resolve("ProductionProviderRuntimeProperties.java");

    private static final Path CYCLE = PACKAGE.resolve("ProviderRuntimeCycleService.java");

    private static final Path SCHEDULER = PACKAGE.resolve("ProviderRuntimeScheduler.java");

    private static final Path OBSERVABILITY = PACKAGE.resolve("ProviderRuntimeObservability.java");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.builder.",
            "com.company.campaign.dev.",
            "com.company.campaign.model.",
            "com.company.campaign.production.model.",
            "com.company.campaign.action.SimulatedActionProvider",
            "com.company.campaign.action.InMemory",
            "com.company.campaign.runtime.InMemory",
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
    void thePackageDependsOnNoBuilderAgentModelDevelopmentSimulatedOrVendorImport() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            Matcher matcher = IMPORT.matcher(read(file));
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
                .as("no Builder, Agent, Skill, Prompt, model, development, simulated provider or vendor import")
                .isEmpty();
    }

    @Test
    void theCoreClassesHoldNoAmbientClockRandomnessSleepOrProcessRead() {
        for (Path file : List.of(CYCLE, SCHEDULER, OBSERVABILITY, PROPERTIES)) {
            String source = read(file);
            for (String forbidden : FORBIDDEN_CONSTRUCTS) {
                assertThat(source)
                        .as(file.getFileName() + " must not contain " + forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void onlyTheConfigurationReadsTheAmbientClock() {
        assertThat(read(CONFIGURATION)).contains("Clock.systemUTC()");
        for (Path file : javaFiles()) {
            if (!file.equals(CONFIGURATION)) {
                assertThat(read(file))
                        .as(file.getFileName().toString())
                        .doesNotContain("Clock.system");
            }
        }
    }

    @Test
    void theSchedulerUsesNoAmbientCommonPool() {
        String source = read(SCHEDULER);
        assertThat(source)
                .contains("new ScheduledThreadPoolExecutor(1")
                .doesNotContain("Executors.newScheduledThreadPool")
                .doesNotContain("Executors.newCachedThreadPool")
                .doesNotContain("ForkJoinPool.commonPool");
    }

    @Test
    void theCycleServiceReturnsOnlyBoundedAggregateCountsAndCodes() {
        String source = read(CYCLE);
        assertThat(source)
                .contains("ProviderRuntimeCycleReport(")
                .contains("ProviderRuntimeCycleStatus.DEGRADED")
                .contains("ProviderRuntimeTenantSource.bounded(")
                .doesNotContain("System.out");
    }

    @Test
    void theConfigurationNamesAndConditionsEveryBeanItPublishes() {
        String source = read(CONFIGURATION);
        assertThat(source)
                .contains("@Bean(name = COMPONENTS_BEAN_NAME)")
                .contains("@Bean(name = CYCLE_BEAN_NAME)")
                .contains("@Bean(name = OBSERVABILITY_BEAN_NAME)")
                .contains("@Bean(name = SCHEDULER_BEAN_NAME, destroyMethod = \"close\")")
                .contains("@Bean(name = TOPOLOGY_EVIDENCE_BEAN_NAME)")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production\", name = \"enabled\", "
                        + "havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.provider-runtime\", "
                        + "name = \"enabled\", havingValue = \"true\")")
                .contains("@ConditionalOnProperty(")
                .contains("name = \"scheduler-enabled\", havingValue = \"true\")");
    }

    @Test
    void thePropertiesAreDefaultOffAndCommitNoHostValue() {
        String source = read(PROPERTIES);
        assertThat(source)
                .contains("private boolean enabled = false;")
                .contains("private boolean schedulerEnabled = false;")
                .contains("@ConfigurationProperties(prefix = \"campaign.production.provider-runtime\")")
                .doesNotContain("https://")
                .doesNotContain("jdbc:")
                .doesNotContain("password =");
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
