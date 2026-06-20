package com.mcplatform.application.punishment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.punishment.port.PunishmentEventPublisher;
import com.mcplatform.application.punishment.port.PunishmentEventStore;
import com.mcplatform.application.punishment.port.PunishmentTemplateNotFoundException;
import com.mcplatform.application.punishment.port.PunishmentTemplateRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.AppliedPunishmentEvent;
import com.mcplatform.domain.punishment.PendingPunishmentEvent;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentConflictException;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.domain.punishment.PunishmentTemplate;
import com.mcplatform.domain.punishment.PunishmentTxId;
import com.mcplatform.domain.punishment.PunishmentType;
import com.mcplatform.domain.punishment.PunishmentValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentServiceTest {

    private final PlayerId target = PlayerId.of(UUID.randomUUID());
    private final PlayerId staff = PlayerId.of(UUID.randomUUID());
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T12:00:00Z"), ZoneOffset.UTC);

    private PunishmentService service(FakeStore store, FakePermissions perms, FakeTemplates templates,
            FakePublisher publisher) {
        return new PunishmentService(store, templates, perms, publisher, clock);
    }

    @Test
    void issueWithoutPermissionIsDeniedAndWritesNothing() {
        FakeStore store = new FakeStore();
        PunishmentService service = service(store, new FakePermissions(), new FakeTemplates(), new FakePublisher());

        assertThatThrownBy(() -> service.issue(target, PunishmentType.TEMPBAN, "x", Duration.ofDays(7),
                staff, PunishmentTxId.random(), "WEB"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(store.issues).isZero();
    }

    @Test
    void issueWithPermissionCreatesActivePunishmentAndPublishes() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions().grant(staff, "punishment.issue.tempban");
        FakePublisher publisher = new FakePublisher();
        PunishmentService service = service(store, perms, new FakeTemplates(), publisher);

        Punishment p = service.issue(target, PunishmentType.TEMPBAN, "Cheating", Duration.ofDays(7),
                staff, PunishmentTxId.random(), "WEB");

        assertThat(p.isActive(clock.instant())).isTrue();
        assertThat(p.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(service.activeFor(target)).hasSize(1);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void secondActiveBanIsRejected() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions()
                .grant(staff, "punishment.issue.tempban")
                .grant(staff, "punishment.issue.permaban");
        PunishmentService service = service(store, perms, new FakeTemplates(), new FakePublisher());

        service.issue(target, PunishmentType.TEMPBAN, "x", Duration.ofDays(1), staff, PunishmentTxId.random(), "WEB");
        assertThatThrownBy(() -> service.issue(target, PunishmentType.PERMABAN, "x", null,
                staff, PunishmentTxId.random(), "WEB"))
                .isInstanceOf(PunishmentConflictException.class);
    }

    @Test
    void chatbanCoexistsWithBan() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions()
                .grant(staff, "punishment.issue.tempban")
                .grant(staff, "punishment.issue.chatban");
        PunishmentService service = service(store, perms, new FakeTemplates(), new FakePublisher());

        service.issue(target, PunishmentType.TEMPBAN, "x", Duration.ofDays(1), staff, PunishmentTxId.random(), "WEB");
        service.issue(target, PunishmentType.CHATBAN, "x", Duration.ofHours(1), staff, PunishmentTxId.random(), "WEB");

        assertThat(service.activeFor(target)).hasSize(2);
    }

    @Test
    void revokeMakesPunishmentInactive() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions()
                .grant(staff, "punishment.issue.tempban")
                .grant(staff, "punishment.revoke");
        PunishmentService service = service(store, perms, new FakeTemplates(), new FakePublisher());

        Punishment issued = service.issue(target, PunishmentType.TEMPBAN, "x", Duration.ofDays(1),
                staff, PunishmentTxId.random(), "WEB");
        Punishment revoked = service.revoke(issued.id(), staff, "appeal granted", PunishmentTxId.random(), "WEB");

        assertThat(revoked.isActive(clock.instant())).isFalse();
        assertThat(service.activeFor(target)).isEmpty();
    }

    @Test
    void issueIsIdempotentOnTransactionIdAndPublishesOnce() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions().grant(staff, "punishment.issue.warn");
        FakePublisher publisher = new FakePublisher();
        PunishmentService service = service(store, perms, new FakeTemplates(), publisher);

        PunishmentTxId tx = PunishmentTxId.random();
        Punishment first = service.issue(target, PunishmentType.WARN, "spamming", null, staff, tx, "WEB");
        Punishment replay = service.issue(target, PunishmentType.WARN, "spamming", null, staff, tx, "WEB");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.issues).isEqualTo(1);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void tempbanWithoutDurationFailsValidation() {
        FakeStore store = new FakeStore();
        FakePermissions perms = new FakePermissions().grant(staff, "punishment.issue.tempban");
        PunishmentService service = service(store, perms, new FakeTemplates(), new FakePublisher());

        assertThatThrownBy(() -> service.issue(target, PunishmentType.TEMPBAN, "x", null,
                staff, PunishmentTxId.random(), "WEB"))
                .isInstanceOf(PunishmentValidationException.class);
    }

    @Test
    void fromTemplateFillsFieldsAndChecksTemplatePermission() {
        FakeStore store = new FakeStore();
        FakeTemplates templates = new FakeTemplates().add(new PunishmentTemplate(
                "cheating", PunishmentType.TEMPBAN, "Cheating", Duration.ofDays(7), "punishment.cheating", true));
        FakePublisher publisher = new FakePublisher();

        // Without the template permission -> denied.
        PunishmentService denied = service(store, new FakePermissions(), templates, publisher);
        assertThatThrownBy(() -> denied.issueFromTemplate(target, "cheating", null, staff, PunishmentTxId.random(), "WEB"))
                .isInstanceOf(PermissionDeniedException.class);

        // With it -> issues a TEMPBAN pre-filled from the template.
        PunishmentService allowed = service(store,
                new FakePermissions().grant(staff, "punishment.cheating"), templates, publisher);
        Punishment p = allowed.issueFromTemplate(target, "cheating", null, staff, PunishmentTxId.random(), "WEB");

        assertThat(p.type()).isEqualTo(PunishmentType.TEMPBAN);
        assertThat(p.reason()).isEqualTo("Cheating");
        assertThat(p.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
    }

    @Test
    void unknownTemplateIsNotFound() {
        PunishmentService service = service(new FakeStore(),
                new FakePermissions().grant(staff, "punishment.cheating"), new FakeTemplates(), new FakePublisher());
        assertThatThrownBy(() -> service.issueFromTemplate(target, "nope", null, staff, PunishmentTxId.random(), "WEB"))
                .isInstanceOf(PunishmentTemplateNotFoundException.class);
    }

    @Test
    void listTemplatesComputesCanApplyPerMember() {
        FakeTemplates templates = new FakeTemplates()
                .add(new PunishmentTemplate("cheating", PunishmentType.TEMPBAN, "Cheating", Duration.ofDays(7),
                        "punishment.cheating", true))
                .add(new PunishmentTemplate("spam", PunishmentType.CHATBAN, "Spam", Duration.ofHours(1),
                        "punishment.spam", true));
        PunishmentService service = service(new FakeStore(),
                new FakePermissions().grant(staff, "punishment.spam"), templates, new FakePublisher());

        List<TemplateView> views = service.listTemplates(staff);

        assertThat(views).hasSize(2);
        assertThat(views).filteredOn(v -> v.template().key().equals("spam")).singleElement()
                .satisfies(v -> assertThat(v.canApply()).isTrue());
        assertThat(views).filteredOn(v -> v.template().key().equals("cheating")).singleElement()
                .satisfies(v -> assertThat(v.canApply()).isFalse());
    }

    // --- fakes -------------------------------------------------------------

    private static final class FakeStore implements PunishmentEventStore {
        final Map<UUID, Punishment> byId = new HashMap<>();
        final Map<UUID, UUID> txToId = new HashMap<>();
        long seq = 0;
        int issues = 0;

        @Override
        public List<Punishment> activeForPlayer(PlayerId player, Instant now) {
            return byId.values().stream()
                    .filter(p -> p.player().equals(player) && p.isActive(now))
                    .toList();
        }

        @Override
        public Optional<Punishment> find(PunishmentId id) {
            return Optional.ofNullable(byId.get(id.value()));
        }

        @Override
        public Optional<Punishment> findByTransactionId(PunishmentTxId tx) {
            UUID id = txToId.get(tx.value());
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }

        @Override
        public Punishment issue(PendingPunishmentEvent event, Instant now) {
            UUID existing = txToId.get(event.transactionId().value());
            if (existing != null) {
                return byId.get(existing);
            }
            if (event.type().category().isExclusive()) {
                boolean clash = byId.values().stream()
                        .anyMatch(p -> p.player().equals(event.player()) && p.isActive(now)
                                && p.category() == event.type().category());
                if (clash) {
                    throw new PunishmentConflictException(event.player(), event.type().category());
                }
            }
            issues++;
            long version = ++seq;
            Punishment p = new Punishment(event.punishmentId(), event.player(), event.type(), event.reason(),
                    event.actor(), event.occurredAt(), event.expiresAt(), null, null, version);
            byId.put(p.id().value(), p);
            txToId.put(event.transactionId().value(), p.id().value());
            return p;
        }

        @Override
        public Punishment revoke(PendingPunishmentEvent event) {
            UUID existing = txToId.get(event.transactionId().value());
            if (existing != null) {
                return byId.get(existing);
            }
            Punishment current = byId.get(event.punishmentId().value());
            if (current == null) {
                throw new com.mcplatform.application.punishment.port.PunishmentNotFoundException(event.punishmentId());
            }
            if (current.isRevoked()) {
                throw new PunishmentConflictException("already revoked");
            }
            long version = ++seq;
            Punishment revoked = new Punishment(current.id(), current.player(), current.type(), current.reason(),
                    current.issuedBy(), current.issuedAt(), current.expiresAt(), event.actor(), event.occurredAt(), version);
            byId.put(revoked.id().value(), revoked);
            txToId.put(event.transactionId().value(), revoked.id().value());
            return revoked;
        }
    }

    private static final class FakePermissions implements PermissionResolver {
        final Set<String> granted = new HashSet<>();

        FakePermissions grant(PlayerId staff, String permission) {
            granted.add(staff.value() + "|" + permission);
            return this;
        }

        @Override
        public boolean hasPermission(UUID staffUuid, String permission) {
            return granted.contains(staffUuid + "|" + permission);
        }
    }

    private static final class FakeTemplates implements PunishmentTemplateRepository {
        final Map<String, PunishmentTemplate> byKey = new HashMap<>();

        FakeTemplates add(PunishmentTemplate t) {
            byKey.put(t.key(), t);
            return this;
        }

        @Override
        public List<PunishmentTemplate> listActive() {
            return byKey.values().stream().filter(PunishmentTemplate::active).toList();
        }

        @Override
        public Optional<PunishmentTemplate> find(String key) {
            return Optional.ofNullable(byKey.get(key));
        }

        @Override
        public void upsert(PunishmentTemplate template, String changedBy) {
            byKey.put(template.key(), template);
        }
    }

    private static final class FakePublisher implements PunishmentEventPublisher {
        final List<AppliedPunishmentEvent> events = new ArrayList<>();

        @Override
        public void publish(AppliedPunishmentEvent event) {
            events.add(event);
        }
    }
}
