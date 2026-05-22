package com.invoice.config;

import com.invoice.tenant.TenantRoutingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;

@Configuration
public class TenantDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * Raw DataSource locked to the 'invoice' schema.
     * Used for schema provisioning and as the JPA default (no-tenant requests).
     * Hibernate creates/updates entity tables in the 'invoice' schema at startup.
     */
    @Bean("rawDataSource")
    public DataSource rawDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(withSchema(jdbcUrl, "invoice"));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("HikariPool-invoice");
        return new HikariDataSource(config);
    }

    /**
     * Primary routing DataSource.
     * When a JWT is present, routes to the tenant's schema (e.g. testcorp_com).
     * When no JWT, falls back to rawDataSource (invoice schema).
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        DataSource defaultDs = rawDataSource();

        TenantRoutingDataSource router = new TenantRoutingDataSource(jdbcUrl, username, password);
        router.setDefaultTargetDataSource(defaultDs);
        router.setTargetDataSources(new HashMap<>());
        router.afterPropertiesSet();
        return router;
    }

    static String withSchema(String url, String schema) {
        String base = url.replaceAll("[?&]currentSchema=[^&]*", "");
        return base.contains("?") ? base + "&currentSchema=" + schema : base + "?currentSchema=" + schema;
    }
}
