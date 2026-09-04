package com.invoice.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes every JPA operation to the correct tenant schema.
 *
 * - No JWT (login, register, admin calls) → falls back to rawDataSource
 * (invoice schema) - JWT with companyDomain present → routes to that company's
 * schema
 *
 * Tables must already exist in the tenant schema (created by
 * SchemaProvisioningService). This class only handles connection routing —
 * never DDL.
 */
@Slf4j
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

	private final String baseJdbcUrl;
	private final String username;
	private final String password;
	private final ConcurrentHashMap<String, DataSource> tenantDataSources = new ConcurrentHashMap<>();

	public TenantRoutingDataSource(String baseJdbcUrl, String username, String password) {
		this.baseJdbcUrl = baseJdbcUrl;
		this.username = username;
		this.password = password;
	}

	@Override
	protected Object determineCurrentLookupKey() {
		return TenantContext.getCurrentTenant();
	}

	@Override
	protected DataSource determineTargetDataSource() {
		String tenant = TenantContext.getCurrentTenant();
		if (tenant == null) {
			return getResolvedDefaultDataSource();
		}
		return tenantDataSources.computeIfAbsent(tenant, this::buildTenantDataSource);
	}

	private DataSource buildTenantDataSource(String schemaName) {
		log.info("Creating connection pool for tenant schema: {}", schemaName);
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(withSchema(schemaName));
		config.setUsername(username);
		config.setPassword(password);
		config.setMaximumPoolSize(5);
		config.setMinimumIdle(1);
		config.setPoolName("TenantPool-" + schemaName);
		return new HikariDataSource(config);
	}

	private String withSchema(String schemaName) {
		// Driver-aware; see TenantDataSourceConfig.withSchema.
		return com.invoice.config.TenantDataSourceConfig.withSchema(baseJdbcUrl, schemaName);
	}
}
