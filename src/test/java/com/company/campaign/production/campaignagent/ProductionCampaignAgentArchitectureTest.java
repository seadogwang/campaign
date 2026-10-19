package com.company.campaign.production.campaignagent;

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
 * T35B: production code no longer depends on any development-only model, identity, store, audit or Preview
 * implementation, and the production Campaign host reuses only the accepted controllers and the accepted
 * request routes.
 *
 * <p>It is a source scan and not a behavioural proof. It exists so a future change that reintroduces a
 * development import, an in-memory fallback, a fake Preview or a request-read identity field fails loudly.
 */
class ProductionCampaignAgentArchitectureTest {

    private static final Path PRODUCTION = Path.of("src", "main", "java", "com", "company", "campaign", "production");

    private static final Path CAMPAIGN_AGENT = PRODUCTION.resolve("campaignagent");

    private static final Path MODEL = PRODUCTION.resolve("model");

    private static final Path CONFIGURATION = CAMPAIGN_AGENT.resolve("ProductionCampaignAgentConfiguration.java");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.company.campaign.dev.",
            "com.company.campaign.preview.adapter",
            "com.company.campaign.query.fixture");

    /** The two T35B packages read no servlet type at all: identity comes only from the trusted-context port. */
    private static final List<String> SERVLET_IMPORTS = List.of("jakarta.servlet", "javax.servlet");

    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "DevelopmentCampaignAgentIdentity",
            "DevelopmentStructuredModelPort",
            "DevelopmentAnthropicModelPort",
            "DevelopmentModelConfiguration",
            "InMemoryDevelopmentConversationStore",
            "InMemoryCampaignConversationAuditSink",
            "FixturePreviewExecutionBackend",
            "PreviewPostgresConfiguration",
            "DevelopmentCampaignAgentAssets");

    @Test
    void theProductionTreeImportsNoDevelopmentOrFixtureType() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(PRODUCTION)) {
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
        assertThat(violations).as("production code depends on no development-only boundary").isEmpty();
    }

    @Test
    void theNewProductionPackagesReadNoServletTypeAtAll() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CAMPAIGN_AGENT)) {
            collectImportViolations(file, SERVLET_IMPORTS, violations);
        }
        for (Path file : javaFiles(MODEL)) {
            collectImportViolations(file, SERVLET_IMPORTS, violations);
        }
        assertThat(violations).as("identity comes only from the trusted-context port").isEmpty();
    }

    private static void collectImportViolations(Path file, List<String> forbidden, List<String> violations) {
        Matcher matcher = IMPORT.matcher(read(file));
        while (matcher.find()) {
            String imported = matcher.group(2);
            for (String prefix : forbidden) {
                if (imported.startsWith(prefix)) {
                    violations.add(file + " -> " + imported);
                }
            }
        }
    }

    @Test
    void theProductionCampaignHostReusesTheAcceptedControllersAndKeepsItsOwnBoundary() {
        String source = read(CONFIGURATION);
        assertThat(source)
                .contains("CampaignAgentConversationController")
                .contains("CampaignAgentTurnController")
                .contains("CampaignAgentConversationHistoryController")
                .contains("CampaignAgentConversationHistoryService")
                .contains("CampaignConversationHistoryStore")
                .contains("CampaignConversationCreateService")
                .contains("CampaignConversationTurnService")
                .contains("DurableCampaignConversationStore")
                .contains("DurableCampaignConversationAuditSink")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production\", name = \"enabled\", "
                        + "havingValue = \"true\")")
                .contains("@ConditionalOnProperty(prefix = \"campaign.production.campaign-agent\", name = "
                        + "\"enabled\", havingValue = \"true\")")
                .contains("prefix = \"campaign.agent.development\", name = \"enabled\", havingValue = \"false\"")
                .doesNotContain("HttpServletRequest")
                .doesNotContain("getHeader(");
    }

    @Test
    void theProductionHostRoutesAreTheAcceptedOnesAndAddNoTrustedRequestField() {
        String conversationController = read(Path.of(
                "src", "main", "java", "com", "company", "campaign", "builder", "campaignagent", "http",
                "CampaignAgentConversationController.java"));
        String turnController = read(Path.of(
                "src", "main", "java", "com", "company", "campaign", "builder", "campaignagent", "http",
                "CampaignAgentTurnController.java"));
        String historyController = read(Path.of(
                "src", "main", "java", "com", "company", "campaign", "builder", "campaignagent", "http",
                "CampaignAgentConversationHistoryController.java"));
        assertThat(conversationController).contains("/api/v1/campaign-agent/conversations");
        assertThat(turnController).contains("/api/v1/campaign-agent/turns");
        assertThat(historyController)
                .contains("/api/v1/campaign-agent/history")
                .contains("@ConditionalOnBean(CampaignAgentConversationHistoryService.class)");
    }

    @Test
    void theProductionPackagesAreNamedForWhatTheyAre() {
        assertThat(javaFiles(CAMPAIGN_AGENT)).isNotEmpty();
        assertThat(javaFiles(MODEL)).isNotEmpty();
        assertThat(CONFIGURATION).exists();
    }

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