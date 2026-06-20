package com.mcplatform.application.player;

import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;

/**
 * Outcome of a session join: the player's identity, whether this join created the player, and the
 * current state of all of their currency balances (freshly initialised or pre-existing).
 */
public record SessionJoin(PlayerId player, String name, boolean created, List<Balance> balances) {
}
