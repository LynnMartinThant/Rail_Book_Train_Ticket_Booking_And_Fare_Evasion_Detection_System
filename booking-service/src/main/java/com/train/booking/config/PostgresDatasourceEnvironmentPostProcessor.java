package com.train.booking.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * When DATABASE_URL is set (e.g. by Render or Heroku with a PostgreSQL add-on),
 * parses it and configures the Spring datasource for PostgreSQL so the same
 * JPA entities and data initializers work with the same schema and data.
 * <p>
 * DATABASE_URL format: postgresql://user:password@host:port/database
 */
public class PostgresDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DATABASE_URL = "DATABASE_URL";
    private static final String PROPERTY_SOURCE_NAME = "postgresDatasourceProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty(DATABASE_URL);
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        if (!databaseUrl.startsWith("postgresql://") && !databaseUrl.startsWith("postgres://")) {
            return;
        }

        Map<String, Object> props = new HashMap<>();
        try {
            // postgresql://user:password@host:port/database
            String rest = databaseUrl.substring(databaseUrl.indexOf("://") + 3);
            int at = rest.lastIndexOf('@');
            if (at < 0) {
                return;
            }
            String userInfo = rest.substring(0, at);
            String hostPortDb = rest.substring(at + 1);

            String user;
            String password = "";
            int firstColon = userInfo.indexOf(':');
            if (firstColon >= 0) {
                user = userInfo.substring(0, firstColon);
                password = userInfo.substring(firstColon + 1);
                try {
                    password = URLDecoder.decode(password, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            } else {
                user = userInfo;
            }

            int slash = hostPortDb.indexOf('/');
            String hostPort = slash >= 0 ? hostPortDb.substring(0, slash) : hostPortDb;
            String database = slash >= 0 ? hostPortDb.substring(slash + 1).split("\\?")[0] : "postgres";

            String host;
            String port = "5432";
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon + 1);
            } else {
                host = hostPort;
            }

            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;

            props.put("spring.datasource.url", jdbcUrl);
            props.put("spring.datasource.username", user);
            props.put("spring.datasource.password", password);
            props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
            props.put("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
            props.put("spring.h2.console.enabled", false);

            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        } catch (Exception ignored) {
            // If parsing fails, leave default (H2) config
        }
    }
}
