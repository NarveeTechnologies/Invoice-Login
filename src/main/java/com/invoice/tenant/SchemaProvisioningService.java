package com.invoice.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class SchemaProvisioningService {

	private static final String SOURCE_SCHEMA = "invoice";

	private final DataSource rawInvoiceDs;
	private final RestTemplate restTemplate;

	@Value("${customer.service.internal.url:http://localhost:5679}")
	private String customerServiceUrl;

	public SchemaProvisioningService(@Qualifier("rawDataSource") DataSource rawInvoiceDs, RestTemplate restTemplate) {
		this.rawInvoiceDs = rawInvoiceDs;
		this.restTemplate = restTemplate;
	}

	/**
	 * On startup: re-clone invoice schema into every existing tenant schema.
	 * Ensures new entity tables (from ddl-auto=update) reach all tenants
	 * automatically.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void syncAllSchemasOnStartup() {
		log.info("Invoice-Login startup: syncing all tenant schemas from '{}'", SOURCE_SCHEMA);
		try (Connection conn = rawInvoiceDs.getConnection()) {
			List<String> tenants = getTenantSchemas(conn);
			log.info("Found {} tenant schema(s) to sync", tenants.size());
			for (String tenant : tenants) {
				try {
					cloneSchema(conn, SOURCE_SCHEMA, tenant);
					log.info("Synced Invoice-Login tables into tenant '{}'", tenant);
				} catch (Exception e) {
					log.error("Failed to sync tenant '{}': {}", tenant, e.getMessage());
				}
			}
		} catch (Exception e) {
			log.error("Startup schema sync failed: {}", e.getMessage());
		}
	}

	/**
	 * Called after company registration. Clones all tables from source schemas into
	 * the new tenant schema across all services.
	 */
	public void provisionTenantSchema(String companyDomain) {
		String schemaName = TenantContext.toSchemaName(companyDomain);
		log.info("Provisioning tenant schema '{}' for domain '{}'", schemaName, companyDomain);

		// 1. Clone invoice schema → tenant schema
		try (Connection conn = rawInvoiceDs.getConnection()) {
			createSchema(conn, schemaName);
			List<String> cloned = cloneSchema(conn, SOURCE_SCHEMA, schemaName);
			log.info("Cloned {} tables (Invoice-Login) into '{}'", cloned.size(), schemaName);
		} catch (Exception e) {
			log.error("Failed to clone invoice schema for tenant '{}': {}", schemaName, e.getMessage());
			throw new RuntimeException("Schema provisioning failed for: " + schemaName, e);
		}

		// 2. Tell Customer-Service to clone invoice schema → tenant schema
		notifyService(customerServiceUrl + "/internal/provision-schema/" + schemaName, "Customer-Service");

		log.info("Tenant schema '{}' provisioned successfully.", schemaName);
	}

	/**
	 * Re-provisions a single tenant schema across all services. Idempotent — safe
	 * to call on existing schemas to pick up new tables.
	 */
	public void reprovisionTenantSchema(String schemaName) {
		log.info("Re-provisioning tenant schema '{}'", schemaName);

		try (Connection conn = rawInvoiceDs.getConnection()) {
			cloneSchema(conn, SOURCE_SCHEMA, schemaName);
		} catch (Exception e) {
			log.error("Re-provision failed for '{}' in Invoice-Login: {}", schemaName, e.getMessage());
		}

		notifyService(customerServiceUrl + "/internal/provision-schema/" + schemaName, "Customer-Service");

		log.info("Re-provisioning of '{}' complete.", schemaName);
	}

	// -----------------------------------------------------------------------
	// SQL-based schema cloning
	// -----------------------------------------------------------------------

	private List<String> getTenantSchemas(Connection conn) throws SQLException {
		List<String> schemas = new ArrayList<>();
		String sql = "SELECT schema_name FROM information_schema.schemata "
				+ "WHERE schema_name NOT IN ('public','invoice','information_schema','pg_catalog','pg_toast','company_registry') "
				+ "AND schema_name NOT LIKE 'pg_%'";
		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next())
				schemas.add(rs.getString("schema_name"));
		}
		return schemas;
	}

	static void createSchema(Connection conn, String schemaName) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
		}
		log.info("Schema '{}' created (or already exists).", schemaName);
	}

	/**
	 * Clones every BASE TABLE from sourceSchema into targetSchema. Uses IF NOT
	 * EXISTS so it is safe to call repeatedly. Per-table errors are logged and
	 * skipped so one bad table doesn't abort the rest.
	 */
	static List<String> cloneSchema(Connection conn, String sourceSchema, String targetSchema) throws SQLException {
		List<String> tables = getBaseTables(conn, sourceSchema);
		List<String> cloned = new ArrayList<>();
		log.info("Cloning {} tables from '{}' → '{}'", tables.size(), sourceSchema, targetSchema);
		for (String table : tables) {
			try {
				cloneTable(conn, sourceSchema, targetSchema, table);
				cloned.add(table);
			} catch (Exception e) {
				log.warn("Skipped table '{}': {}", table, e.getMessage());
			}
		}
		log.info("Cloned {}/{} tables from '{}' → '{}'", cloned.size(), tables.size(), sourceSchema, targetSchema);
		return cloned;
	}

	private static List<String> getBaseTables(Connection conn, String schema) throws SQLException {
		List<String> tables = new ArrayList<>();
		String sql = "SELECT table_name FROM information_schema.tables "
				+ "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
				+ "AND table_name NOT IN ('flyway_schema_history') " + "ORDER BY table_name";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, schema);
			ResultSet rs = ps.executeQuery();
			while (rs.next())
				tables.add(rs.getString("table_name"));
		}
		return tables;
	}

	private static void cloneTable(Connection conn, String src, String tgt, String table) throws SQLException {
		String ddl = String.format(
				"CREATE TABLE IF NOT EXISTS \"%s\".\"%s\" (LIKE \"%s\".\"%s\" INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)",
				tgt, table, src, table);
		try (Statement stmt = conn.createStatement()) {
			stmt.execute(ddl);
		}
		fixSequences(conn, tgt, table);
	}

	private static void fixSequences(Connection conn, String tgtSchema, String table) throws SQLException {
		String sql = "SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = ? AND table_name = ? AND column_default LIKE 'nextval%'";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, tgtSchema);
			ps.setString(2, table);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				String col = rs.getString("column_name");
				String seqName = table + "_" + col + "_seq";
				try (Statement stmt = conn.createStatement()) {
					stmt.execute(String.format("CREATE SEQUENCE IF NOT EXISTS \"%s\".\"%s\"", tgtSchema, seqName));
					stmt.execute(String.format(
							"ALTER TABLE \"%s\".\"%s\" ALTER COLUMN \"%s\" SET DEFAULT nextval('\"%s\".\"%s\"')",
							tgtSchema, table, col, tgtSchema, seqName));
					stmt.execute(String.format("ALTER SEQUENCE \"%s\".\"%s\" OWNED BY \"%s\".\"%s\".\"%s\"", tgtSchema,
							seqName, tgtSchema, table, col));
				}
			}
		}
	}

	private void notifyService(String url, String serviceName) {
		try {
			restTemplate.postForEntity(url, null, String.class);
			log.info("{} provisioned via {}", serviceName, url);
		} catch (Exception e) {
			log.warn("{} provisioning call failed: {}", serviceName, e.getMessage());
		}
	}
}
