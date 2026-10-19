package com.company.campaign.dev.campaignagent;

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
 * T32 requirement 8: the core Campaign Agent package stays free of every host dependency; the network,
 * configuration and session implementations live in the development adapter package; and no committed
 * configuration carries a credential, production URL or production-like identity.
 */
class CampaignAgentHostArchitectureTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path CAMPAIGN_AGENT =
            MAIN.resolve(Path.of("com", "company", "campaign", "builder", "campaignagent"));
    private static final Path WEB_EDGE = CAMPAIGN_AGENT.resolve("http");
    private static final Path DEVELOPMENT_HOST =
            MAIN.resolve(Path.of("com", "company", "campaign", "dev", "campaignagent"));
    private static final Path CONVERSATION =
            MAIN.resolve(Path.of("com", "company", "campaign", "conversation"));
    private static final Path CONVERSATION_ADAPTERS = CONVERSATION.resolve("postgres");

    private static final List<String> FORBIDDEN_CORE_IMPORTS = List.of(
            "org.springframework.",
            "java.net.",
            "java.sql",
            "javax.sql",
            "javax.persistence",
            "jakarta.persistence",
            "org.hibernate",
            "java.time.Clock",
            "java.util.Random",
            "com.company.campaign.dev",
            "com.company.campaign.conversation",
            "com.company.campaign.localhealth",
            "com.company.campaign.lifecycle",
            "com.company.campaign.runtime",
            "com.company.campaign.fullpublish",
            "com.company.campaign.CampaignApplication");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+)\\s*;");

    @Test
    void theCoreAgentPackageReachesNoHostDependency() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CAMPAIGN_AGENT)) {
            if (file.startsWith(WEB_EDGE)) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(read(file));
            while (matcher.find()) {
                String imported = matcher.group(2);
                for (String forbidden : FORBIDDEN_CORE_IMPORTS) {
                    if (imported.startsWith(forbidden)) {
                        violations.add(file + " -> " + imported);
                    }
                }
            }
        }
        assertThat(violations).as("core Campaign Agent imports stay host-free").isEmpty();
    }

    @Test
    void theHostImplementationsLiveInTheDevelopmentAdapterPackage() {
        assertThat(fileNames(DEVELOPMENT_HOST)).contains(
                "DevelopmentCampaignAgentConfiguration.java",
                "DevelopmentCampaignAgentTurnService.java",
                "DevelopmentCampaignRoutingModel.java",
                "DevelopmentSkillInputResolvers.java",
                "InMemoryDevelopmentConversationStore.java",
                "InMemoryCampaignConversationAuditSink.java");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(CONVERSATION)) {
            if (file.startsWith(CONVERSATION_ADAPTERS)) {
                continue;
            }
            String source = read(file);
            if (source.contains("org.springframework.") || source.contains("java.net.")) {
                violations.add(file.toString());
            }
        }
        assertThat(violations).as("the conversation port package stays a port package").isEmpty();
    }

    @Test
    void theModelOutputTypesCannotNameAToolOrConstructAnArtifact() {
        assertThat(read(CAMPAIGN_AGENT.resolve("CampaignRoutingDecision.java")))
                .doesNotContain("CampaignSkillArtifact")
                .doesNotContain("toolId")
                .doesNotContain("Map<");
        assertThat(read(CAMPAIGN_AGENT.resolve("CampaignRoutingModelPort.java")))
                .doesNotContain("CampaignSkillArtifact")
                .doesNotContain("ToolId");
        assertThat(read(CAMPAIGN_AGENT.resolve("CampaignSkillInputResolver.java")))
                .doesNotContain("Map<String, Object>")
                .doesNotContain("JsonNode");
    }

    @Test
    void noCommittedConfigurationCarriesASecretOrProductionUrl() {
        String configuration = read(Path.of("src", "main", "resources", "application.yml"));
        assertThat(configuration)
                .doesNotContain("https://")
                .doesNotContain("http://")
                .doesNotContain("sk-")
                .doesNotContain("Bearer ");
        assertThat(Pattern.compile("(?m)^\\s*(token|password|secret|username|url|apikey)\\s*:")
                .matcher(configuration).find()).isFalse();
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> fileNames(Path root) {
        return javaFiles(root).stream().map(path -> path.getFileName().toString()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
