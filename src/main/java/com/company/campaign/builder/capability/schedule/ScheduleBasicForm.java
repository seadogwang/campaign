package com.company.campaign.builder.capability.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The closed set of schedule forms the {@code schedule.basic} capability contract version 1.0 accepts.
 *
 * <p>The form is a fact of the capability contract, not of a caller: a payload names exactly one of these
 * three machine codes and nothing else. The order is meaningful — it is the order the pack declares in its
 * frozen payload contract — so the declared enumeration and this enum can never drift apart.
 *
 * <p>A form carries no occurrence semantics. This build validates and normalizes the declared shape only; it
 * never generates occurrences, resolves a DST transition, creates scheduling work or computes an execution
 * key. The summary code is a bounded machine code a safe preview card may carry; it names the form and
 * nothing about a member, a message or a provider.
 */
public enum ScheduleBasicForm {

    /** Run once, immediately, in the declared canonical timezone. */
    IMMEDIATE,

    /** Run once at a declared offset instant that matches the declared canonical timezone. */
    ONCE,

    /** Run daily between two inclusive calendar dates, inside the bounded window. */
    DAILY;

    /**
     * The closed declared codes, in declaration order.
     *
     * <p>This is what the frozen payload contract enumerates, so a payload cannot name a form this build
     * does not implement and the metadata cannot advertise a form the resolver refuses.
     */
    public static List<String> codes() {
        List<String> codes = new ArrayList<>(values().length);
        for (ScheduleBasicForm form : values()) {
            codes.add(form.name());
        }
        return List.copyOf(codes);
    }

    /**
     * Parses one declared machine code, returning empty for any token this build does not declare.
     *
     * <p>The rejected token is never returned or echoed: it is external input and must not become observable.
     */
    public static Optional<ScheduleBasicForm> parse(String token) {
        if (token != null) {
            for (ScheduleBasicForm form : values()) {
                if (form.name().equals(token)) {
                    return Optional.of(form);
                }
            }
        }
        return Optional.empty();
    }

    /** The bounded machine code a safe preview card may carry for this form. */
    public String summaryCode() {
        return "SCHEDULE_" + name();
    }
}