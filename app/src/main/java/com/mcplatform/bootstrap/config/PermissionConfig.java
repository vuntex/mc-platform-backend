package com.mcplatform.bootstrap.config;

import com.mcplatform.application.permission.GrantExpiryService;
import com.mcplatform.application.permission.PermissionAdminService;
import com.mcplatform.application.permission.PermissionQueryService;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.bootstrap.adapter.RedisPermissionEventPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the permission/rank use cases. Reuses the existing {@code Clock} and the
 * {@link PermissionResolver} (the unchanged read port, here also the write-path gate). The publisher
 * bridges permission changes onto Redis Pub/Sub ({@code mc:permission:changed}) for live, relog-free
 * effect (FR-020/FR-021).
 */
@Configuration
public class PermissionConfig {

    @Bean
    PermissionChangePublisher permissionChangePublisher(RedisCacheAdapter redis, Clock clock) {
        return new RedisPermissionEventPublisher(redis, clock);
    }

    @Bean
    PermissionAdminService permissionAdminService(RoleRepository roles, PlayerGrantRepository grants,
            GrantAuditPort audit, PermissionChangePublisher publisher, PermissionResolver permissions,
            Clock clock) {
        return new PermissionAdminService(roles, grants, audit, publisher, permissions, clock);
    }

    @Bean
    PermissionQueryService permissionQueryService(RoleRepository roles, PlayerGrantRepository grants,
            Clock clock) {
        return new PermissionQueryService(roles, grants, clock);
    }

    @Bean
    GrantExpiryService grantExpiryService(PlayerGrantRepository grants, GrantAuditPort audit,
            PermissionChangePublisher publisher, Clock clock,
            @Value("${mcplatform.permission.system-actor-uuid:00000000-0000-0000-0000-0000000000aa}") UUID systemActor) {
        return new GrantExpiryService(grants, audit, publisher, clock, systemActor);
    }
}
