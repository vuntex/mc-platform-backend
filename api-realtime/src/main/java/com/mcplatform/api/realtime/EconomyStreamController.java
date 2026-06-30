package com.mcplatform.api.realtime;

import com.mcplatform.application.economy.EconomyPermissions;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Web-interface-only live balance stream ({@code GET /api/web/economy/stream[?player=]}, spec 007 US4).
 * Behind the {@code /api/web/**} JWT chain (401 without a token); gated against
 * {@code permission.economy.read} via the {@link PermissionResolver} (403). Registers the client with
 * the {@link EconomyStreamRegistry}; an optional {@code player} restricts delivery to that player's
 * events (server-side filter). Read-only consumer of the existing {@code mc:economy:balance} pub/sub —
 * no new event or codec; player names are resolved client-side.
 */
@RestController
public class EconomyStreamController {

    private final EconomyStreamRegistry registry;
    private final PermissionResolver resolver;

    public EconomyStreamController(EconomyStreamRegistry registry, PermissionResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
    }

    @GetMapping(path = "/api/web/economy/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal PlayerId actor,
            @RequestParam(required = false) UUID player) {
        requireEconomyRead(actor);
        SseEmitter emitter = new SseEmitter(0L); // no timeout; closed on client disconnect
        registry.register(emitter, player);
        return emitter;
    }

    private void requireEconomyRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), EconomyPermissions.READ)) {
            throw new PermissionDeniedException(actor.value(), EconomyPermissions.READ);
        }
    }
}
