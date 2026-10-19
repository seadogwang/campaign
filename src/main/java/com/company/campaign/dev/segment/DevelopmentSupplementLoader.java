package com.company.campaign.dev.segment;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class DevelopmentSupplementLoader {

    private static final int MAX_RESOURCE_BYTES = 1_048_576;
    private static final CanonicalJson JSON = new CanonicalJson();

    private DevelopmentSupplementLoader() {
    }

    static DevelopmentMetadataSupplement metadata(String path) {
        return read(path, DevelopmentMetadataSupplement.class);
    }

    static DevelopmentStorageSupplement storage(String path) {
        return read(path, DevelopmentStorageSupplement.class);
    }

    private static <T> T read(String path, Class<T> type) {
        if (path == null || path.isBlank() || path.contains("..")) {
            throw new IllegalStateException("Invalid development supplement resource path");
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream input = DevelopmentSupplementLoader.class.getClassLoader().getResourceAsStream(normalized)) {
            if (input == null) {
                throw new IllegalStateException("Development supplement resource is unavailable");
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException("Development supplement resource exceeds the size limit");
            }
            return JSON.read(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (IOException failure) {
            throw new IllegalStateException("Development supplement resource cannot be read", failure);
        }
    }
}
