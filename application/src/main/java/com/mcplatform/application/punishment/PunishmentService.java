package com.mcplatform.application.punishment;

import com.mcplatform.application.punishment.port.PunishmentEventPublisher;
import com.mcplatform.application.punishment.port.PunishmentEventStore;
import com.mcplatform.application.punishment.port.PunishmentNotFoundException;
import com.mcplatform.application.punishment.port.PunishmentTemplateNotFoundException;
import com.mcplatform.application.punishment.port.PunishmentTemplateRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.AppliedPunishmentEvent;
import com.mcplatform.domain.punishment.PendingPunishmentEvent;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentEventType;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.domain.punishment.PunishmentPolicy;
import com.mcplatform.domain.punishment.PunishmentTemplate;
import com.mcplatform.domain.punishment.PunishmentTxId;
import com.mcplatform.domain.punishment.PunishmentType;
import com.mcplatform.domain.punishment.PunishmentValidationException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application use case for punishments. Orchestrates the authoritative write path:
 * permission check (backend-authoritative) → idempotency check → let the domain compute the event
 * (coexistence rule) → append + project (persistence re-checks exclusivity under a player lock) →
 * publish the live-update event.
 *
 * <p>Permissions are checked FIRST and BEFORE any write. Idempotency is checked by transaction id: a
 * replay returns the recorded result without re-publishing. Publishing happens AFTER the DB commit and
 * is best-effort — a failure there never fails the operation, since Postgres is the source of truth
 * (mirrors {@code EconomyService}).
 */
public final class PunishmentService {

    private static final Logger LOG = System.getLogger(PunishmentService.class.getName());

    /** Permission required to revoke any punishment. */
    public static final String REVOKE_PERMISSION = "punishment.revoke";

    private final PunishmentEventStore store;
    private final PunishmentTemplateRepository templates;
    private final PermissionResolver permissions;
    private final PunishmentEventPublisher publisher;
    private final Clock clock;

    public PunishmentService(PunishmentEventStore store, PunishmentTemplateRepository templates,
            PermissionResolver permissions, PunishmentEventPublisher publisher, Clock clock) {
        this.store = store;
        this.templates = templates;
        this.permissions = permissions;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Permission required to directly issue a punishment of {@code type}, e.g. {@code punishment.issue.tempban}. */
    public static String issuePermission(PunishmentType type) {
        return "punishment.issue." + type.name().toLowerCase();
    }

    /** Active punishments for a player at the current time. */
    public List<Punishment> activeFor(PlayerId player) {
        return store.activeForPlayer(player, clock.instant());
    }

    /** Templates with a per-template {@code canApply} flag for the querying team member. */
    public List<TemplateView> listTemplates(PlayerId staff) {
        return templates.listActive().stream()
                .map(t -> new TemplateView(t, permissions.hasPermission(staff.value(), t.requiredPermission())))
                .toList();
    }

    /** Directly issue a punishment. Requires the per-type issue permission. */
    public Punishment issue(PlayerId player, PunishmentType type, String reason, Duration duration,
            PlayerId issuedBy, PunishmentTxId tx, String source) {
        requirePermission(issuedBy, issuePermission(type));
        return doIssue(player, type, reason, duration, issuedBy, tx, source);
    }

    /** Issue from a template: requires the template's permission; pre-fills type/reason/duration. */
    public Punishment issueFromTemplate(PlayerId player, String templateKey, String reasonOverride,
            PlayerId issuedBy, PunishmentTxId tx, String source) {
        PunishmentTemplate template = templates.find(templateKey)
                .filter(PunishmentTemplate::active)
                .orElseThrow(() -> new PunishmentTemplateNotFoundException(templateKey));
        requirePermission(issuedBy, template.requiredPermission());
        String reason = (reasonOverride == null || reasonOverride.isBlank())
                ? template.defaultReason() : reasonOverride;
        return doIssue(player, template.type(), reason, template.duration(), issuedBy, tx, source);
    }

    /** Revoke a punishment before its natural expiry. Requires {@link #REVOKE_PERMISSION}. */
    public Punishment revoke(PunishmentId id, PlayerId revokedBy, String reason, PunishmentTxId tx, String source) {
        requirePermission(revokedBy, REVOKE_PERMISSION);
        Instant now = clock.instant();

        Optional<Punishment> replay = store.findByTransactionId(tx);
        if (replay.isPresent()) {
            return replay.get();
        }
        Punishment existing = store.find(id).orElseThrow(() -> new PunishmentNotFoundException(id));
        PendingPunishmentEvent event = new PendingPunishmentEvent(id, existing.player(),
                PunishmentEventType.REVOKED, null, reason, revokedBy, now, null, tx, source);
        Punishment revoked = store.revoke(event);
        safePublish(applied(event, revoked));
        return revoked;
    }

    private Punishment doIssue(PlayerId player, PunishmentType type, String reason, Duration duration,
            PlayerId issuedBy, PunishmentTxId tx, String source) {
        Instant now = clock.instant();
        Instant expiresAt = resolveExpiry(type, duration, now);

        Optional<Punishment> replay = store.findByTransactionId(tx);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<Punishment> active = store.activeForPlayer(player, now);
        PendingPunishmentEvent event = PunishmentPolicy.issue(active, PunishmentId.random(), player,
                type, reason, issuedBy, now, expiresAt, tx, source);
        Punishment issued = store.issue(event, now);
        safePublish(applied(event, issued));
        return issued;
    }

    /** Time-bound types need a positive duration; the rest have no expiry. */
    private static Instant resolveExpiry(PunishmentType type, Duration duration, Instant now) {
        if (type.isTimeBound()) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new PunishmentValidationException(type + " requires a positive duration");
            }
            return now.plus(duration);
        }
        return null; // WARN, PERMABAN: permanent / not applicable
    }

    private void requirePermission(PlayerId actor, String permission) {
        if (!permissions.hasPermission(actor.value(), permission)) {
            throw new PermissionDeniedException(actor.value(), permission);
        }
    }

    private static AppliedPunishmentEvent applied(PendingPunishmentEvent event, Punishment result) {
        return new AppliedPunishmentEvent(result.id(), result.player(), result.type(), event.eventType(),
                event.reason(), event.actor(), result.expiresAt(), result.version(), event.occurredAt());
    }

    private void safePublish(AppliedPunishmentEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "punishment event publish failed (non-fatal)", e);
        }
    }
}
