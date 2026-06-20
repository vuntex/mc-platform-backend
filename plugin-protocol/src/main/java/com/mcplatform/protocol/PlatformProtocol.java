package com.mcplatform.protocol;

import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.economy.BalanceChangedEventCodec;
import com.mcplatform.protocol.punishment.PunishmentChangedEventCodec;

/**
 * Single assembly point for the shared message protocol: lists every feature codec the backend and
 * plugin understand. Adding a feature = one plug-in here (register its {@code MessageCodec}, alongside
 * its own channel constant + event + codec). Both sides build their protocol from {@link #create()}
 * so routing stays in sync.
 */
public final class PlatformProtocol {

    private PlatformProtocol() {}

    /** Builds a {@link MessageProtocol} with all known feature codecs registered. */
    public static MessageProtocol create() {
        return new MessageProtocol(
                BalanceChangedEventCodec.INSTANCE,
                PunishmentChangedEventCodec.INSTANCE
                // future feature codecs plug in here (cosmetics, permissions, stats, ...)
        );
    }
}
