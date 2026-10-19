package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** External authoring form for a versioned registered-default registry. */
public record RegisteredDefaultsResource(
        String registryVersion,
        List<Entry> entries
) {

    public RegisteredDefaultSource publish() {
        List<RegisteredDefaultSource.RegisteredDefaultRevision> revisions = entries == null
                ? List.of()
                : entries.stream().map(Entry::publish).toList();
        return new RegisteredDefaultSource.Registry(registryVersion, revisions);
    }

    public record Entry(String defaultId, String version, String value) {
        RegisteredDefaultSource.RegisteredDefaultRevision publish() {
            if (value == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.DEFAULT_REFERENCE_INVALID,
                        "A registered-default resource entry requires a value");
            }
            return new RegisteredDefaultSource.RegisteredDefaultRevision(
                    defaultId,
                    version,
                    ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8)),
                    value);
        }
    }
}
