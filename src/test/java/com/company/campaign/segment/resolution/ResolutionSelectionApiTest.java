package com.company.campaign.segment.resolution;

import com.company.campaign.segment.intent.SegmentIntent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction 3: the selection-bearing resolver overload is internal implementation only. External callers can
 * reach resolution only through the selection-free public API, and the sole public selection application
 * boundary remains {@link ClarificationChoiceService}, which re-proves the exact {@link ClarificationChoice}
 * receipt before applying anything.
 */
class ResolutionSelectionApiTest {

    @Test
    void theResolverExposesNoPublicSelectionBearingMethod() {
        List<Method> resolveMethods = Arrays.stream(IntentResolver.class.getMethods())
                .filter(method -> method.getName().equals("resolve"))
                .toList();

        assertThat(resolveMethods).isNotEmpty();
        assertThat(resolveMethods).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getParameterCount()).isEqualTo(1);
            assertThat(method.getParameterTypes()[0]).isEqualTo(SegmentIntent.class);
            assertThat(method.getReturnType()).isEqualTo(ResolutionResult.class);
        });
        assertThat(IntentResolver.class.getMethods())
                .noneMatch(method -> method.getName().equals("resolve")
                        && Map.class.isAssignableFrom(method.getParameterTypes()[0]));
    }

    @Test
    void theSelectionOverloadExistsOnlyAsInternalImplementation() throws Exception {
        Method selection = IntentResolver.class.getDeclaredMethod(
                "resolve", SegmentIntent.class, Map.class);

        assertThat(Modifier.isPublic(selection.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(selection.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(selection.getModifiers()))
                .as("the choice service is a separate class and needs the package-private boundary")
                .isFalse();
    }

    @Test
    void theChoiceServiceIsThePublicSelectionApplicationBoundary() throws Exception {
        Method apply = ClarificationChoiceService.class.getDeclaredMethod(
                "apply", SegmentIntent.class, ClarificationChoice.class);

        assertThat(Modifier.isPublic(apply.getModifiers())).isTrue();
        assertThat(apply.getReturnType()).isEqualTo(ClarificationChoiceResult.class);
        assertThat(ClarificationChoiceService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("resolve"))
                .isEmpty();
    }
}