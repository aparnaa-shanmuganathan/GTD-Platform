package com.gtd.platform;

import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Spring context against a real PostgreSQL 16 database
 * (Testcontainers or embedded-postgres, see {@link PostgresIntegrationTestBase})
 * and confirms the application starts cleanly end to end - including
 * datasource connectivity and the (currently empty) Flyway migration run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GtdPlatformApplicationSmokeTest extends PostgresIntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsAgainstRealPostgres() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(GtdPlatformApplication.class)).isNotNull();
    }
}
