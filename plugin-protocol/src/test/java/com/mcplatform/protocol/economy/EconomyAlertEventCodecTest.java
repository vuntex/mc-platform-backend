package com.mcplatform.protocol.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Wire round-trip for the economy alert, including the nullable target and a reason with delimiters. */
class EconomyAlertEventCodecTest {

    private static final EconomyAlertEventCodec CODEC = EconomyAlertEventCodec.INSTANCE;
    private static final UUID FROM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TO = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void roundTripTransferWithTarget() {
        EconomyAlertEvent e = new EconomyAlertEvent(FROM, TO, "COINS", "TRANSFER",
                100_000L, 5_000L, 1_000_000L, "10% des Umlaufs, 90% des Sender-Guthabens", 1_700_000_000_000L);

        EconomyAlertEvent back = CODEC.decodePayload(CODEC.encodePayload(e));

        assertEquals(e, back);
        assertEquals(TO, back.targetUuid());
    }

    @Test
    void roundTripSingleNullTarget() {
        EconomyAlertEvent e = new EconomyAlertEvent(FROM, null, "COINS", "SET",
                2_000_000L, 2_000_000L, 3_000_000L, "66% des Umlaufs", 1_700_000_000_001L);

        EconomyAlertEvent back = CODEC.decodePayload(CODEC.encodePayload(e));

        assertEquals(e, back);
        assertNull(back.targetUuid());
    }
}
