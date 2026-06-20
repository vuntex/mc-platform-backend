package com.mcplatform.api.rest;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.session.PlayerRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Minimal player master-data endpoint (upsert) — needed so balances can reference a player. */
@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerRepository players;

    public PlayerController(PlayerRepository players) {
        this.players = players;
    }

    @PutMapping("/{uuid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsert(@PathVariable UUID uuid, @RequestBody PlayerRequest request) {
        players.save(PlayerId.of(uuid), request.name(), Instant.now());
    }
}
