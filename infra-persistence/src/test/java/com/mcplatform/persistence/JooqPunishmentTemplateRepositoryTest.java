package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_TEMPLATE;
import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_TEMPLATE_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.punishment.PunishmentTemplate;
import com.mcplatform.domain.punishment.PunishmentType;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test for the template repository: seeded reads + the audited upsert path. */
@Testcontainers
class JooqPunishmentTemplateRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqPunishmentTemplateRepository templates;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        templates = new JooqPunishmentTemplateRepository(dsl);
    }

    @Test
    void readsSeededTemplates() {
        assertThat(templates.listActive()).extracting(PunishmentTemplate::key)
                .contains("cheating", "spam", "warn_minor");

        assertThat(templates.find("cheating")).hasValueSatisfying(t -> {
            assertThat(t.type()).isEqualTo(PunishmentType.TEMPBAN);
            assertThat(t.duration()).isEqualTo(Duration.ofDays(7));
            assertThat(t.requiredPermission()).isEqualTo("punishment.cheating");
        });
    }

    @Test
    void upsertWritesAnAuditTrail() {
        PunishmentTemplate created = new PunishmentTemplate("toxicity", PunishmentType.CHATBAN,
                "Toxic chat", Duration.ofHours(2), "punishment.toxicity", true);
        templates.upsert(created, "admin@example.com");

        assertThat(templates.find("toxicity")).isPresent();
        long inserts = dsl.fetchCount(PUNISHMENT_TEMPLATE_AUDIT,
                PUNISHMENT_TEMPLATE_AUDIT.TEMPLATE_KEY.eq("toxicity"));
        assertThat(inserts).as("create writes one audit row").isEqualTo(1);

        // Updating the same key bumps the version and writes a second audit row whose old_value is set.
        PunishmentTemplate updated = new PunishmentTemplate("toxicity", PunishmentType.CHATBAN,
                "Toxic chat (revised)", Duration.ofHours(6), "punishment.toxicity", true);
        templates.upsert(updated, "admin@example.com");

        assertThat(dsl.fetchCount(PUNISHMENT_TEMPLATE_AUDIT, PUNISHMENT_TEMPLATE_AUDIT.TEMPLATE_KEY.eq("toxicity")))
                .isEqualTo(2);
        Long version = dsl.select(PUNISHMENT_TEMPLATE.VERSION).from(PUNISHMENT_TEMPLATE)
                .where(PUNISHMENT_TEMPLATE.KEY.eq("toxicity")).fetchOne(PUNISHMENT_TEMPLATE.VERSION);
        assertThat(version).isEqualTo(1L);
        assertThat(templates.find("toxicity")).hasValueSatisfying(t ->
                assertThat(t.defaultReason()).isEqualTo("Toxic chat (revised)"));
    }
}
