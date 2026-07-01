package com.mcplatform.application.player;

import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.application.economy.port.CurrencyDefault;
import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.player.port.PlayerPresencePort;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Application use case for "a player joined a server" (reported by the plugin). Ensures the player
 * exists and, only for a brand-new player, initialises the configured default balances.
 *
 * <p>The player upsert is a single atomic statement whose return value ({@code created}) is the
 * authority on newness — under concurrent joins from multiple nodes exactly one call wins the insert.
 * Default balances are seeded through the existing event-sourced economy use case
 * ({@link EconomyService#credit}) so the starting amount enters via the event store (full audit
 * trail, replayable projection). The credit's idempotency key is derived deterministically from
 * (player, currency), so even if initialisation were attempted twice it can never credit twice.
 */
public final class PlayerSessionService {

    /** Source tag for the system-issued initial CREDITED event. */
    static final String INITIAL_SOURCE = "SYSTEM:initial";

    private final PlayerRepository players;
    private final CurrencyRepository currencies;
    private final EconomyService economy;
    private final PlayerPresencePort presence;

    public PlayerSessionService(PlayerRepository players, CurrencyRepository currencies, EconomyService economy,
            PlayerPresencePort presence) {
        this.players = players;
        this.currencies = currencies;
        this.economy = economy;
        this.presence = presence;
    }

    public SessionJoin join(PlayerId player, String name) {
        boolean created = players.upsertReturningWhetherNew(player, name, Instant.now());
        List<CurrencyDefault> configured = currencies.findAll();

        List<Balance> balances = new ArrayList<>(configured.size());
        for (CurrencyDefault currency : configured) {
            if (created) {
                balances.add(initialise(player, currency));
            } else {
                balances.add(economy.balance(player, currency.code()));
            }
        }
        presence.markOnline(player); // best-effort; the presence store degrades gracefully if unavailable
        return new SessionJoin(player, name, created, balances);
    }

    /**
     * A player disconnected: refresh {@code last_seen} (they were present until now, so recency ordering
     * stays correct) and clear their live presence. Idempotent.
     */
    public void leave(PlayerId player) {
        players.touchLastSeen(player, Instant.now());
        presence.markOffline(player);
    }

    /** Seed one currency for a new player: credit the configured default, or just materialise a 0 row. */
    private Balance initialise(PlayerId player, CurrencyDefault currency) {
        if (currency.defaultBalance().units() > 0) {
            return economy.credit(player, currency.code(), currency.defaultBalance(),
                    TransactionId.forInitialBalance(player, currency.code()), INITIAL_SOURCE);
        }
        return economy.ensureZeroBalance(player, currency.code());
    }
}
