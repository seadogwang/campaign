package com.company.campaign.dev.segment;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads development model configuration without ever writing or logging credentials. */
public record DevelopmentModelConfiguration(String token, String baseUrl, String model) {

    private static final CanonicalJson JSON = new CanonicalJson();

    public DevelopmentModelConfiguration {
        token = required(token, "ANTHROPIC_AUTH_TOKEN");
        baseUrl = required(baseUrl, "ANTHROPIC_BASE_URL");
        model = required(model, "ANTHROPIC_MODEL");
    }

    public static DevelopmentModelConfiguration load() {
        String token = System.getenv("ANTHROPIC_AUTH_TOKEN");
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        String model = System.getenv("ANTHROPIC_MODEL");
        if (present(token) && present(baseUrl) && present(model)) {
            return new DevelopmentModelConfiguration(token, baseUrl, model);
        }
        JsonNode settings = readClaudeSettings();
        return new DevelopmentModelConfiguration(
                configured("ANTHROPIC_AUTH_TOKEN", token, settings),
                configured("ANTHROPIC_BASE_URL", baseUrl, settings),
                configured("ANTHROPIC_MODEL", model, settings));
    }

    private static String configured(String name, String environment, JsonNode settings) {
        if (present(environment)) {
            return environment;
        }
        JsonNode value = settings.path("env").path(name);
        return value.isTextual() ? value.asText() : null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static JsonNode readClaudeSettings() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("Development model configuration is unavailable");
        }
        Path path = Path.of(userHome, ".claude", "settings.json");
        try {
            byte[] bytes = Files.readAllBytes(path);
            return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Development model configuration is unavailable");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing development model configuration: " + name);
        }
        return value;
    }
}
