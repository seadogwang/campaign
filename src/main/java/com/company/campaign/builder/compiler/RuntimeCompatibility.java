package com.company.campaign.builder.compiler;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Frozen preview/runtime compatibility facts declared by one capability contract version.
 *
 * <p>These are facts, not behavior: T15 records them so a later task can decide whether a resolved Manifest
 * is previewable or publishable at all. The compiler never executes Preview or Runtime, never calls a
 * provider and never inspects a runtime registry.
 */
public record RuntimeCompatibility(String runtimeSchemaVersion, boolean previewSupported, boolean publishable) {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");

    public RuntimeCompatibility {
        if (runtimeSchemaVersion == null || !VERSION.matcher(runtimeSchemaVersion).matches()) {
            throw new IllegalArgumentException("runtimeSchemaVersion must be a bounded version token");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuntimeCompatibility compatibility
                && previewSupported == compatibility.previewSupported
                && publishable == compatibility.publishable
                && Objects.equals(runtimeSchemaVersion, compatibility.runtimeSchemaVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runtimeSchemaVersion, previewSupported, publishable);
    }
}