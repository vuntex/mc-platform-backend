package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.SessionMapper;
import com.mcplatform.application.player.PlayerSessionService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionJoinResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session lifecycle for a player (reported by the plugin) — distinct from economy operations: this is
 * player/session domain. A join ensures the player exists and their default balances are initialised,
 * idempotently; a leave clears the player's live presence.
 */
@RestController
@RequestMapping("/api/players/{uuid}/session")
public class PlayerSessionController {

    private final PlayerSessionService sessions;

    public PlayerSessionController(PlayerSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/join")
    public SessionJoinResponse join(@PathVariable UUID uuid, @RequestBody PlayerRequest request) {
        return SessionMapper.sessionJoinResponse(sessions.join(PlayerId.of(uuid), request.name()));
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID uuid) {
        sessions.leave(PlayerId.of(uuid));
    }
}
