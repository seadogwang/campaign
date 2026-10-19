package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Strict classpath loader for externally authored registered defaults. */
public final class RegisteredDefaultsResourceLoader {

    private static final int MAX_RESOURCE_BYTES = 262_144;
    private static final CanonicalJson JSON = new CanonicalJson();

    private RegisteredDefaultsResourceLoader() {
    }

    public static RegisteredDefaultSource load(String resourcePath) {
        String normalized = normalize(resourcePath);
        try (InputStream input = RegisteredDefaultsResourceLoader.class.getClassLoader()
                .getResourceAsStream(normalized)) {
            if (input == null) {
                throw invalid("A registered-default resource is unavailable");
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw invalid("A registered-default resource exceeds the size limit");
            }
            RegisteredDefaultsResource resource = JSON.read(
                    new String(bytes, StandardCharsets.UTF_8), RegisteredDefaultsResource.class);
            return resource.publish();
        } catch (IOException failure) {
            throw invalid("A registered-default resource cannot be read");
        }
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || resourcePath.contains("..")) {
            throw invalid("A registered-default resource path is invalid");
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    private static IntentException invalid(String message) {
        return IntentException.invalid(IntentErrorCodes.DEFAULT_REFERENCE_INVALID, message);
    }
}
