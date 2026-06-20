package com.mcplatform.application.punishment;

import com.mcplatform.domain.punishment.PunishmentTemplate;

/**
 * A template paired with whether the querying team member may apply it — {@code canApply} is computed
 * backend-side from the member's permissions against the template's {@code requiredPermission}.
 */
public record TemplateView(PunishmentTemplate template, boolean canApply) {
}
