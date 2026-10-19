package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Strict classpath loader for externally authored resolution policies. */
public final class ResolutionPolicyResourceLoader {

    private static final int MAX_RESOURCE_BYTES = 262_144;
    private static final CanonicalJson JSON = new CanonicalJson();

    private ResolutionPolicyResourceLoader() {
    }

    public static ResolutionPolicy load(String resourcePath) {
        String normalized = normalize(resourcePath);
        try (InputStream input = ResolutionPolicyResourceLoader.class.getClassLoader()
                .getResourceAsStream(normalized)) {
            if (input == null) {
                throw invalid("A resolution-policy resource is unavailable");
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw invalid("A resolution-policy resource exceeds the size limit");
            }
            ResolutionPolicyResource resource = JSON.read(
                    new String(bytes, StandardCharsets.UTF_8), ResolutionPolicyResource.class);
            return resource.publish();
        } catch (IOException failure) {
            throw invalid("A resolution-policy resource cannot be read");
        }
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || resourcePath.contains("..")) {
            throw invalid("A resolution-policy resource path is invalid");
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    private static ResolutionException invalid(String message) {
        return new ResolutionException(ResolutionErrorCodes.POLICY_INVALID, message);
    }
}
