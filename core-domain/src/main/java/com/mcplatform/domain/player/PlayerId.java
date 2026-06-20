package com.mcplatform.domain.player;

import java.util.Objects;
import java.util.UUID;

/**
 * UUID-centric player identity. Every economy reference points at this id;
 * the player name is only a cache field elsewhere (PROGRESS.md section 3).
 */
public record PlayerId(UUID value) {

    public PlayerId {
        Objects.requireNonNull(value, "player id must not be null");
    }

    public static PlayerId of(UUID value) {
        return new PlayerId(value);
    }
}
