package com.company.campaign.journey.compiler;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The bounded, immutable outcome of one deterministic Journey compile.
 *
 * <p>A successful compilation carries the compiled {@link JourneyManifest} and no issue. A failed compilation
 * carries no Manifest and the deterministic, bounded graph/node issue list that rejected it — it is never a
 * partially-built Journey and it never truncates silently.
 */
public record JourneyCompilation(JourneyManifest manifest, List<Issue> issues) {

    public JourneyCompilation {
        issues = List.copyOf(issues);
    }

    /** True when a Manifest was produced. */
    public boolean isCompiled() {
        return manifest != null;
    }

    /**
     * The compiled Manifest, or a bounded rejection when compilation failed.
     *
     * <p>It fails closed rather than returning a partial artifact, so a caller cannot mistake an invalid graph
     * for a compiled Journey.
     */
    public JourneyManifest requireManifest() {
        if (manifest == null) {
            throw new JourneyCompileException(JourneyCompileErrorCodes.GRAPH_INVALID,
                    "graphValid", "The Journey graph did not compile.", issues);
        }
        return manifest;
    }

    @Override
    public String toString() {
        return "JourneyCompilation[compiled=" + isCompiled() + ", issues=" + issues.size() + ']';
    }
}
