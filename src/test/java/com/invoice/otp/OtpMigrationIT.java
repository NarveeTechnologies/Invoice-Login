package com.invoice.otp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The migration must work on both a fresh database and an existing one.
 *
 * <p>The existing case is the one that can go wrong silently. Real deployments
 * have a populated `invoice` schema built by Hibernate, already carrying two
 * Flyway history tables belonging to other services, and the legacy plaintext
 * `otp` table with live rows in it. Flyway reporting "success" against that is
 * not evidence: {@code baseline-on-migrate} on a non-empty schema writes a
 * baseline marker and then skips every migration at or below
 * {@code baseline-version}, whose default is 1 — which would skip
 * {@code V001__otp_challenges.sql} while reporting success and creating nothing.
 *
 * <p>So every test here asserts on the database objects, never on Flyway's
 * return value.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpMigrationIT {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("invoice")
					.withUsername("invoice")
					.withPassword("invoice");

	private JdbcTemplate connect() {
		POSTGRES.start();
		DriverManagerDataSource ds = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		ds.setDriverClassName("org.postgresql.Driver");
		return new JdbcTemplate(ds);
	}

	/** Runs Flyway exactly as application.properties configures it. */
	private void migrate(JdbcTemplate jdbc, String schema) {
		Flyway.configure()
				.dataSource(jdbc.getDataSource())
				.schemas(schema).defaultSchema(schema)
				.table("flyway_schema_history_login")
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	private boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
		Integer n = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				 WHERE table_schema = ? AND table_name = ?
				""", Integer.class, schema, table);
		return n != null && n == 1;
	}

	@Test
	@DisplayName("fresh database: the schema is created and usable")
	void freshDatabase() {
		JdbcTemplate jdbc = connect();
		String schema = "fresh_" + System.nanoTime() % 100000;
		jdbc.execute("CREATE SCHEMA " + schema);

		migrate(jdbc, schema);

		assertTrue(tableExists(jdbc, schema, "otp_challenges"),
				"V001 did not run — check spring.flyway.baseline-version");
		assertEquals("001", jdbc.queryForObject(
				"SELECT version FROM " + schema + ".flyway_schema_history_login "
						+ "WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
				String.class));
	}

	@Test
	@DisplayName("existing database: V001 still applies over a populated schema")
	void existingPopulatedDatabase() {
		JdbcTemplate jdbc = connect();
		String schema = "existing_" + System.nanoTime() % 100000;
		jdbc.execute("CREATE SCHEMA " + schema);
		jdbc.execute("SET search_path TO " + schema);

		// Reproduce a real deployed schema: application tables built by
		// Hibernate, and the two Flyway history tables other services own.
		jdbc.execute("CREATE TABLE " + schema + ".user_info "
				+ "(id bigserial PRIMARY KEY, email varchar(255), primary_email varchar(255))");
		jdbc.execute("CREATE TABLE " + schema + ".manage_users "
				+ "(id bigserial PRIMARY KEY, email varchar(255), full_name varchar(255))");
		jdbc.execute("CREATE TABLE " + schema + ".flyway_schema_history_invoice "
				+ "(installed_rank int PRIMARY KEY, version varchar(50), success boolean)");
		jdbc.execute("CREATE TABLE " + schema + ".flyway_schema_history "
				+ "(installed_rank int PRIMARY KEY, version varchar(50), success boolean)");
		jdbc.update("INSERT INTO " + schema + ".user_info (email, primary_email) VALUES (?,?)",
				"existing@example.com", "existing@example.com");

		// And the legacy plaintext OTP table, with a live row.
		jdbc.execute("CREATE TABLE " + schema + ".otp "
				+ "(otp_id bigserial PRIMARY KEY, email varchar(255), otp varchar(255), "
				+ "expiry_time bigint)");
		jdbc.update("INSERT INTO " + schema + ".otp (email, otp, expiry_time) VALUES (?,?,?)",
				"legacy@example.com", "A1B2C3", System.currentTimeMillis() + 120000);

		migrate(jdbc, schema);

		assertAll(
				() -> assertTrue(tableExists(jdbc, schema, "otp_challenges"),
						"V001 was skipped on a non-empty schema — this is the "
								+ "baseline-version trap, and Flyway reports success anyway"),
				() -> assertFalse(tableExists(jdbc, schema, "otp"),
						"the legacy plaintext otp table should be dropped"),
				// Nothing else may be disturbed.
				() -> assertTrue(tableExists(jdbc, schema, "user_info")),
				() -> assertTrue(tableExists(jdbc, schema, "manage_users")),
				() -> assertEquals(1, jdbc.queryForObject(
						"SELECT count(*) FROM " + schema + ".user_info", Integer.class),
						"existing application data must survive"),
				// The other services' history tables are untouched.
				() -> assertTrue(tableExists(jdbc, schema, "flyway_schema_history_invoice")),
				() -> assertTrue(tableExists(jdbc, schema, "flyway_schema_history")),
				() -> assertTrue(tableExists(jdbc, schema, "flyway_schema_history_login"),
						"this service must use its own history table"));
	}

	@Test
	@DisplayName("the default baseline-version silently skips V001 — the trap, demonstrated")
	void defaultBaselineVersionSkipsTheMigration() {
		// Not a hypothetical. Flyway's baselineVersion defaults to 1, and on a
		// non-empty schema baseline-on-migrate writes that marker and then skips
		// every migration at or below it. V001 is at it. Flyway then reports a
		// successful run having created nothing, and the first OTP request fails
		// on a missing relation — far from the deploy that caused it.
		//
		// This test exists so that anyone who "tidies up" the explicit
		// baseline-version=0 out of application.properties sees why it is there
		// — and, more importantly, that deploying once without it leaves a
		// schema that later config fixes cannot repair on their own.
		JdbcTemplate jdbc = connect();
		String schema = "trap_" + System.nanoTime() % 100000;
		jdbc.execute("CREATE SCHEMA " + schema);
		// Non-empty, as every real deployment is.
		jdbc.execute("CREATE TABLE " + schema + ".user_info (id bigserial PRIMARY KEY)");

		Flyway.configure()
				.dataSource(jdbc.getDataSource())
				.schemas(schema).defaultSchema(schema)
				.table("flyway_schema_history_login")
				.baselineOnMigrate(true)
				// baselineVersion deliberately left at its default of 1.
				.locations("classpath:db/migration")
				.load()
				.migrate();   // reports success

		assertFalse(tableExists(jdbc, schema, "otp_challenges"),
				"if this now passes, Flyway changed its default and the explicit "
						+ "baseline-version=0 may no longer be load-bearing — re-check "
						+ "before relaxing it");

		// And the part that makes this genuinely dangerous rather than merely
		// annoying: correcting the configuration afterwards does NOT repair it.
		// The baseline row is already in the history table at version 1, and
		// V001 is still at or below it, so a second run with baseline-version=0
		// skips the migration exactly as the first did — and again reports
		// success.
		migrate(jdbc, schema);
		assertFalse(tableExists(jdbc, schema, "otp_challenges"),
				"the schema recovered on its own — if so this hazard is smaller than "
						+ "documented and the runbook can be relaxed");

		// Recovery needs the baseline row removed, which is an operator action.
		jdbc.update("DELETE FROM " + schema + ".flyway_schema_history_login "
				+ "WHERE type = 'BASELINE'");
		migrate(jdbc, schema);
		assertTrue(tableExists(jdbc, schema, "otp_challenges"),
				"removing the stale baseline row is what lets V001 finally run");
	}

	@Test
	@DisplayName("re-running the migration is a no-op")
	void migrationIsIdempotent() {
		JdbcTemplate jdbc = connect();
		String schema = "rerun_" + System.nanoTime() % 100000;
		jdbc.execute("CREATE SCHEMA " + schema);

		migrate(jdbc, schema);
		migrate(jdbc, schema);

		Integer applied = jdbc.queryForObject(
				"SELECT count(*) FROM " + schema + ".flyway_schema_history_login "
						+ "WHERE version = '001'", Integer.class);
		assertEquals(1, applied, "V001 was applied twice");
		assertTrue(tableExists(jdbc, schema, "otp_challenges"));
	}

	@Test
	@DisplayName("every column, index and constraint the code depends on exists")
	void schemaShapeIsComplete() {
		JdbcTemplate jdbc = connect();
		String schema = "shape_" + System.nanoTime() % 100000;
		jdbc.execute("CREATE SCHEMA " + schema);
		migrate(jdbc, schema);

		List<String> columns = jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = ? AND table_name = 'otp_challenges'
				""", String.class, schema);

		assertTrue(columns.containsAll(List.of(
				"id", "identifier_hash", "purpose", "code_hash", "expires_at", "consumed_at",
				"attempt_count", "max_attempts", "invalidated_at", "invalidated_reason",
				"created_at", "updated_at", "ip_hash", "user_agent_hash", "correlation_id",
				"account_exists")), "missing columns; actual = " + columns);

		List<String> indexes = jdbc.queryForList(
				"SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'otp_challenges'",
				String.class, schema);
		assertTrue(indexes.stream().anyMatch(i -> i.contains("lookup")),
				"the verification lookup index is missing; actual = " + indexes);
		assertTrue(indexes.stream().anyMatch(i -> i.contains("identifier_window")),
				"the per-identifier rate-limit index is missing");
		assertTrue(indexes.stream().anyMatch(i -> i.contains("ip_window")),
				"the per-IP rate-limit index is missing");

		List<Map<String, Object>> checks = jdbc.queryForList("""
				SELECT con.conname FROM pg_constraint con
				  JOIN pg_class rel ON rel.oid = con.conrelid
				  JOIN pg_namespace ns ON ns.oid = rel.relnamespace
				 WHERE ns.nspname = ? AND rel.relname = 'otp_challenges' AND con.contype = 'c'
				""", schema);
		assertEquals(3, checks.size(),
				"expected the purpose, attempts and invalidation CHECK constraints; got " + checks);
	}
}
