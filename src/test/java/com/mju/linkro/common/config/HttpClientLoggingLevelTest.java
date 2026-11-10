package com.mju.linkro.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDK HTTP client loggers print full request URLs at FINE/FINEST, and the Seoul arrival key is in the URL path.
 * They must stay at INFO or above.
 */
@SpringBootTest
@ActiveProfiles("test")
class HttpClientLoggingLevelTest {
    private static final Set<LogLevel> SAFE = Set.of(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL, LogLevel.OFF);

    @Test
    void applicationYamlPinsTheJdkHttpClientLoggers() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        String level = yaml.getObject().getProperty("logging.level.sun.net.www");
        assertThat(level).isNotNull();
        assertThat(SAFE).contains(LogLevel.valueOf(level.strip().toUpperCase()));
    }

    /** In a started context the loggers inherit INFO or above from sun.net.www, so FINE request lines are dropped. */
    @Test
    void theRunningLevelIsInfoOrAbove() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String logger : new String[] {"sun.net.www.protocol.http.HttpURLConnection", "sun.net.www.http.HttpClient"}) {
            Level level = context.getLogger(logger).getEffectiveLevel();
            assertThat(level.isGreaterOrEqual(Level.INFO)).as(logger + " is " + level).isTrue();
            assertThat(context.getLogger(logger).isDebugEnabled()).as(logger).isFalse();
        }
    }
}
