package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;

/**
 * Outcome of an atomic refresh-token rotation (computed inside one repository transaction):
 * <ul>
 *   <li>{@link Rotated} — the presented token was active; it is now consumed and a successor was inserted.</li>
 *   <li>{@link Replay} — the presented token was already rotated (theft signal); ALL of the player's tokens
 *       have been deleted in the same transaction.</li>
 *   <li>{@link Invalid} — unknown or expired token; nothing changed.</li>
 * </ul>
 */
public sealed interface RotateResult permits RotateResult.Rotated, RotateResult.Replay, RotateResult.Invalid {

    record Rotated(PlayerId player) implements RotateResult {}

    record Replay(PlayerId player) implements RotateResult {}

    record Invalid() implements RotateResult {}
}
