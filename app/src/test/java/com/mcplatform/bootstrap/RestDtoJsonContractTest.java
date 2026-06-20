package com.mcplatform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionJoinResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Proves the shared (dependency-free) protocol DTOs map to/from exactly the JSON the backend speaks on
 * the wire. We deliberately keep plugin-protocol JSON-free: since the protocol records are now the
 * single source for the wire shape, a "field alignment vs a backend DTO" test would be a tautology
 * (there is no separate backend DTO anymore). The real risk is whether these pure records still
 * (de)serialize to the documented field names — a JSON concern, so the proof lives here in {@code app}
 * with Spring's real Jackson ({@code @JsonTest} — Jackson only, no DB/Redis container).
 */
@JsonTest
class RestDtoJsonContractTest {

    @Autowired
    ObjectMapper json;

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void balanceResponseMatchesBackendJson() throws Exception {
        BalanceResponse dto = new BalanceResponse(PLAYER, "COINS", 100L, 5L);

        assertThat(json.readTree(json.writeValueAsString(dto)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("player", "currency", "balance", "version");

        BalanceResponse parsed = json.readValue(
                "{\"player\":\"00000000-0000-0000-0000-000000000001\",\"currency\":\"COINS\","
                        + "\"balance\":100,\"version\":5}", BalanceResponse.class);
        assertThat(parsed).isEqualTo(dto);
    }

    @Test
    void amountRequestMatchesBackendJson() throws Exception {
        // server-generated id + default source: both fields omitted on the wire -> null
        AmountRequest parsed = json.readValue("{\"amount\":50}", AmountRequest.class);
        assertThat(parsed).isEqualTo(new AmountRequest(50L, null, null));

        AmountRequest full = new AmountRequest(50L, OTHER, "PLUGIN:shop");
        assertThat(json.readValue(json.writeValueAsString(full), AmountRequest.class)).isEqualTo(full);
    }

    @Test
    void transferRequestAndResponseMatchBackendJson() throws Exception {
        TransferRequest req = json.readValue(
                "{\"to\":\"00000000-0000-0000-0000-0000000000bb\",\"amount\":30}", TransferRequest.class);
        assertThat(req).isEqualTo(new TransferRequest(OTHER, 30L, null, null));

        TransferResponse resp = new TransferResponse(
                new BalanceResponse(PLAYER, "COINS", 70L, 8L),
                new BalanceResponse(OTHER, "COINS", 30L, 9L));
        assertThat(json.readTree(json.writeValueAsString(resp)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("from", "to");
        assertThat(json.readValue(json.writeValueAsString(resp), TransferResponse.class)).isEqualTo(resp);
    }

    @Test
    void sessionDtosMatchBackendJson() throws Exception {
        PlayerRequest player = json.readValue("{\"name\":\"Steve\"}", PlayerRequest.class);
        assertThat(player).isEqualTo(new PlayerRequest("Steve"));

        SessionJoinResponse join = new SessionJoinResponse(PLAYER, "Steve", true,
                List.of(new BalanceResponse(PLAYER, "COINS", 100L, 1L)));
        assertThat(json.readTree(json.writeValueAsString(join)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("player", "name", "created", "balances");
        assertThat(json.readValue(json.writeValueAsString(join), SessionJoinResponse.class)).isEqualTo(join);
    }
}
