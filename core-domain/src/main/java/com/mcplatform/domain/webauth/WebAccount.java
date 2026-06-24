package com.mcplatform.domain.webauth;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/**
 * Web-login account of a player, anchored to exactly one Minecraft identity (UUID). State-stored, not
 * event-sourced (Constitution principle 6): identity/config data, no money/judgement aggregate. Holds an
 * irreversible password hash only — never plaintext, never an email or username (spec FR-001/020).
 */
public record WebAccount(PlayerId playerUuid, String passwordHash, Instant createdAt, Instant passwordUpdatedAt) {
}
