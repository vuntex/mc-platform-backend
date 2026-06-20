package com.mcplatform.application.punishment.port;

import com.mcplatform.domain.punishment.PunishmentTemplate;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for punishment templates — config managed via the web interface. Writes are audited
 * (old/new snapshot), mirroring {@code server_config}/{@code config_audit}.
 */
public interface PunishmentTemplateRepository {

    /** All active templates (the ones offered for application). */
    List<PunishmentTemplate> listActive();

    /** A single template by key (regardless of active flag), if present. */
    Optional<PunishmentTemplate> find(String key);

    /** Insert or update a template, writing an audit row capturing the change. */
    void upsert(PunishmentTemplate template, String changedBy);
}
