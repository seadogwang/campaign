package com.company.campaign.testsupport;

import com.company.campaign.shared.error.ContractException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Assertions for the stable machine codes carried by {@link ContractException}. */
public final class ContractAssertions {

    private ContractAssertions() {
    }

    /** Asserts that the callable fails with a contract failure carrying exactly the expected code. */
    public static void assertFailsWithCode(ThrowingCallable callable, String expectedCode) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown)
                .as("expected a contract failure with code %s", expectedCode)
                .isInstanceOf(ContractException.class);
        assertThat(((ContractException) thrown).code())
                .as("stable machine code")
                .isEqualTo(expectedCode);
    }
}