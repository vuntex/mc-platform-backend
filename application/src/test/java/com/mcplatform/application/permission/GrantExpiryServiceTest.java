package com.mcplatform.application.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.permission.PermissionFakes.FakeAudit;
import com.mcplatform.application.permission.PermissionFakes.FakeGrantRepository;
import com.mcplatform.application.permission.PermissionFakes.FakePublisher;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrantExpiryServiceTest {

    private final Instant now = Instant.parse("2026-06-23T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID system = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private FakeGrantRepository grants;
    private FakeAudit audit;
    private FakePublisher publisher;
    private GrantExpiryService svc;

    @BeforeEach
    void setUp() {
        grants = new FakeGrantRepository();
        audit = new FakeAudit();
        publisher = new FakePublisher();
        svc = new GrantExpiryService(grants, audit, publisher, clock, system);
    }

    private RoleGrant expiredRoleGrant(PlayerId player, long roleId) {
        return new RoleGrant(player, RoleId.of(roleId), UUID.randomUUID(), now.minusSeconds(3600),
                now.minusSeconds(1), null, true);
    }

    @Test
    void expiredGrantIsDeactivatedAuditedAndPublishedOnce() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        grants.upsertRoleGrant(expiredRoleGrant(player, 1));

        int swept = svc.sweep();

        assertThat(swept).isEqualTo(1);
        assertThat(grants.activeRoleGrants(player, now)).isEmpty();
        assertThat(audit.count(GrantAuditPort.Action.EXPIRE)).isEqualTo(1);
        assertThat(audit.entries).singleElement().satisfies(e -> assertThat(e.actor()).isEqualTo(system));
        assertThat(publisher.countOfType(PermissionChangeType.GRANT_EXPIRED)).isEqualTo(1);
    }

    @Test
    void twoExpiredGrantsForSamePlayerProduceExactlyOneEvent() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        grants.upsertRoleGrant(expiredRoleGrant(player, 1));
        grants.upsertPermissionGrant(new PermissionGrant(player, "kit.vip", UUID.randomUUID(),
                now.minusSeconds(3600), now.minusSeconds(1), null, true));

        int swept = svc.sweep();

        assertThat(swept).isEqualTo(2);
        assertThat(audit.count(GrantAuditPort.Action.EXPIRE)).isEqualTo(2);
        assertThat(publisher.countOfType(PermissionChangeType.GRANT_EXPIRED)).isEqualTo(1); // one per UUID
    }

    @Test
    void unexpiredGrantIsLeftAlone() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        grants.upsertRoleGrant(new RoleGrant(player, RoleId.of(1), UUID.randomUUID(), now.minusSeconds(10),
                now.plusSeconds(3600), null, true));

        assertThat(svc.sweep()).isZero();
        assertThat(publisher.events).isEmpty();
    }
}
