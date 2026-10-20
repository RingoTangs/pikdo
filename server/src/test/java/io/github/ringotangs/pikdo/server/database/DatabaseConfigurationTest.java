package io.github.ringotangs.pikdo.server.database;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** No PostgreSQL connection or Docker is needed to reject missing configuration. */
class DatabaseConfigurationTest {
    @Test
    void missingUrlDoesNotFallBackToAnEmbeddedOrFakeDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.embedded-database-connection=none")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
