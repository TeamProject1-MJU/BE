package com.mju.linkro.subway.mock;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when a subway switch is missing, blank or {@code mock} (case and surrounding spaces ignored). Unlike
 * {@code @ConditionalOnProperty(havingValue = "mock", matchIfMissing = true)}, an empty value such as
 * {@code LINKRO_SUBWAY_CONGESTION=} also keeps the mock, so the app never starts without the bean.
 */
abstract class MockSwitchCondition extends SpringBootCondition {
    private final String property;

    MockSwitchCondition(String property) {
        this.property = property;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(property);
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("mock")) {
            return ConditionOutcome.match(property + " is missing, empty or mock");
        }
        return ConditionOutcome.noMatch(property + " is " + value.strip());
    }

    static final class Module extends MockSwitchCondition {
        Module() {
            super("linkro.subway.module");
        }
    }

    static final class Congestion extends MockSwitchCondition {
        Congestion() {
            super("linkro.subway.congestion");
        }
    }
}
