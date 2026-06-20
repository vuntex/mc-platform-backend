package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_TEMPLATE;
import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_TEMPLATE_AUDIT;

import com.mcplatform.application.punishment.port.PunishmentTemplateRepository;
import com.mcplatform.domain.punishment.PunishmentTemplate;
import com.mcplatform.domain.punishment.PunishmentType;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

/**
 * jOOQ adapter for punishment templates. Reads serve the list/apply paths; {@link #upsert} writes the
 * template AND an audit row (old/new JSON snapshot) in one transaction — the CRUD+audit pattern of
 * {@code server_config}/{@code config_audit}, so template edits made via the web interface are
 * traceable. No Spring.
 */
public final class JooqPunishmentTemplateRepository implements PunishmentTemplateRepository {

    private final DSLContext dsl;

    public JooqPunishmentTemplateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PunishmentTemplate> listActive() {
        return dsl.selectFrom(PUNISHMENT_TEMPLATE)
                .where(PUNISHMENT_TEMPLATE.ACTIVE.isTrue())
                .orderBy(PUNISHMENT_TEMPLATE.KEY.asc())
                .fetch(JooqPunishmentTemplateRepository::toTemplate);
    }

    @Override
    public Optional<PunishmentTemplate> find(String key) {
        Record rec = dsl.selectFrom(PUNISHMENT_TEMPLATE)
                .where(PUNISHMENT_TEMPLATE.KEY.eq(key))
                .fetchOne();
        return rec == null ? Optional.empty() : Optional.of(toTemplate(rec));
    }

    @Override
    public void upsert(PunishmentTemplate template, String changedBy) {
        Long durationMillis = template.duration() == null ? null : template.duration().toMillis();
        dsl.transaction(cfg -> {
            DSLContext tx = cfg.dsl();
            Record old = tx.selectFrom(PUNISHMENT_TEMPLATE)
                    .where(PUNISHMENT_TEMPLATE.KEY.eq(template.key()))
                    .fetchOne();
            JSONB oldValue = old == null ? null : toJson(toTemplate(old), old.get(PUNISHMENT_TEMPLATE.DURATION_MILLIS));

            tx.insertInto(PUNISHMENT_TEMPLATE)
                    .set(PUNISHMENT_TEMPLATE.KEY, template.key())
                    .set(PUNISHMENT_TEMPLATE.TYPE, template.type().name())
                    .set(PUNISHMENT_TEMPLATE.DEFAULT_REASON, template.defaultReason())
                    .set(PUNISHMENT_TEMPLATE.DURATION_MILLIS, durationMillis)
                    .set(PUNISHMENT_TEMPLATE.REQUIRED_PERMISSION, template.requiredPermission())
                    .set(PUNISHMENT_TEMPLATE.ACTIVE, template.active())
                    .set(PUNISHMENT_TEMPLATE.VERSION, 0L)
                    .set(PUNISHMENT_TEMPLATE.UPDATED_BY, changedBy)
                    .onConflict(PUNISHMENT_TEMPLATE.KEY)
                    .doUpdate()
                    .set(PUNISHMENT_TEMPLATE.TYPE, template.type().name())
                    .set(PUNISHMENT_TEMPLATE.DEFAULT_REASON, template.defaultReason())
                    .set(PUNISHMENT_TEMPLATE.DURATION_MILLIS, durationMillis)
                    .set(PUNISHMENT_TEMPLATE.REQUIRED_PERMISSION, template.requiredPermission())
                    .set(PUNISHMENT_TEMPLATE.ACTIVE, template.active())
                    .set(PUNISHMENT_TEMPLATE.VERSION, PUNISHMENT_TEMPLATE.VERSION.plus(1))
                    .set(PUNISHMENT_TEMPLATE.UPDATED_BY, changedBy)
                    .set(PUNISHMENT_TEMPLATE.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .execute();

            tx.insertInto(PUNISHMENT_TEMPLATE_AUDIT)
                    .set(PUNISHMENT_TEMPLATE_AUDIT.TEMPLATE_KEY, template.key())
                    .set(PUNISHMENT_TEMPLATE_AUDIT.OLD_VALUE, oldValue)
                    .set(PUNISHMENT_TEMPLATE_AUDIT.NEW_VALUE, toJson(template, durationMillis))
                    .set(PUNISHMENT_TEMPLATE_AUDIT.CHANGED_BY, changedBy)
                    .execute();
        });
    }

    private static PunishmentTemplate toTemplate(Record r) {
        Long ms = r.get(PUNISHMENT_TEMPLATE.DURATION_MILLIS);
        return new PunishmentTemplate(
                r.get(PUNISHMENT_TEMPLATE.KEY),
                PunishmentType.valueOf(r.get(PUNISHMENT_TEMPLATE.TYPE)),
                r.get(PUNISHMENT_TEMPLATE.DEFAULT_REASON),
                ms == null ? null : Duration.ofMillis(ms),
                r.get(PUNISHMENT_TEMPLATE.REQUIRED_PERMISSION),
                Boolean.TRUE.equals(r.get(PUNISHMENT_TEMPLATE.ACTIVE)));
    }

    /** Minimal hand-built JSON snapshot for the audit row (no JSON library in this module). */
    private static JSONB toJson(PunishmentTemplate t, Long durationMillis) {
        String json = "{"
                + "\"key\":\"" + esc(t.key()) + "\","
                + "\"type\":\"" + t.type().name() + "\","
                + "\"default_reason\":\"" + esc(t.defaultReason()) + "\","
                + "\"duration_millis\":" + (durationMillis == null ? "null" : durationMillis) + ","
                + "\"required_permission\":\"" + esc(t.requiredPermission()) + "\","
                + "\"active\":" + t.active()
                + "}";
        return JSONB.valueOf(json);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
