package com.mcplatform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcplatform.application.economy.BalanceStreamView;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.EconomyEventEntry;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import com.mcplatform.protocol.economy.PlayerBalanceEntry;
import com.mcplatform.protocol.economy.PlayerBalancesResponse;
import com.mcplatform.protocol.economy.TransactionDetailResponse;
import com.mcplatform.protocol.economy.TransactionLegDto;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionJoinResponse;
import com.mcplatform.protocol.webauth.LoginRequest;
import com.mcplatform.protocol.webauth.TokenPairResponse;
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
    void playerBalancesResponseMatchesBackendJson() throws Exception {
        PlayerBalancesResponse dto = new PlayerBalancesResponse(PLAYER,
                List.of(new PlayerBalanceEntry("COINS", "Coins", "$", 0, 100L)));

        assertThat(json.readTree(json.writeValueAsString(dto)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("player", "balances");
        assertThat(json.readTree(json.writeValueAsString(dto)).get("balances").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("currencyCode", "displayName", "symbol", "decimalPlaces", "balance");
        assertThat(json.readValue(json.writeValueAsString(dto), PlayerBalancesResponse.class)).isEqualTo(dto);
    }

    @Test
    void economyHistoryEntryCarriesPlayerFieldsOnTheWire() throws Exception {
        EconomyEventEntry entry = new EconomyEventEntry(412L, "COINS", "CREDITED", 50L, 50L, OTHER,
                "WEBSHOP", null, null, 1_750_000_000_000L, PLAYER, "Steve");
        EconomyHistoryResponse resp = new EconomyHistoryResponse(null, List.of(entry), 405L);

        assertThat(json.readTree(json.writeValueAsString(resp)).get("entries").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("sequenceNo", "currencyCode", "eventType", "amount", "balanceAfter",
                        "transactionId", "source", "correlationId", "counterpartyUuid", "timestampEpochMilli",
                        "playerUuid", "playerName");
        assertThat(json.readValue(json.writeValueAsString(resp), EconomyHistoryResponse.class)).isEqualTo(resp);
    }

    @Test
    void transactionDetailResponseMatchesBackendJson() throws Exception {
        TransactionDetailResponse dto = new TransactionDetailResponse(PLAYER, null, "SINGLE", "COINS",
                "Coins", "$", 0, 42L, "WEB", "{\"k\":\"v\"}", 1_750_000_000_000L,
                List.of(new TransactionLegDto(OTHER, "Steve", "CREDITED", 42L)));

        assertThat(json.readTree(json.writeValueAsString(dto)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("transactionId", "correlationId", "kind", "currencyCode",
                        "displayName", "symbol", "decimalPlaces", "amount", "source", "metadata",
                        "timestampEpochMilli", "legs");
        assertThat(json.readTree(json.writeValueAsString(dto)).get("legs").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("playerUuid", "playerName", "eventType", "balanceAfter");
        assertThat(json.readValue(json.writeValueAsString(dto), TransactionDetailResponse.class)).isEqualTo(dto);
    }

    @Test
    void balanceStreamViewIsTheSseFrameContract() throws Exception {
        // The SSE data: frame is exactly this view serialised — pin its field names (FR-020: no name).
        BalanceStreamView view = new BalanceStreamView(PLAYER, "COINS", "CREDITED", 10L, 110L, 413L,
                OTHER, "WEB", null, 1_750_000_000_000L);
        assertThat(json.readTree(json.writeValueAsString(view)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("playerUuid", "currencyCode", "eventType", "amount", "balance",
                        "version", "transactionId", "source", "correlationId", "timestampEpochMilli");
        assertThat(json.writeValueAsString(view)).doesNotContain("playerName");
    }

    @Test
    void webSessionDtosMatchBackendJson() throws Exception {
        LoginRequest login = json.readValue("{\"username\":\"Vuntex\",\"password\":\"secret123\"}", LoginRequest.class);
        assertThat(login).isEqualTo(new LoginRequest("Vuntex", "secret123"));

        // TokenPairResponse carries the access token + expiries, but NEVER a refreshToken field.
        TokenPairResponse pair = new TokenPairResponse("jwt-abc", 111L, 222L);
        assertThat(json.readTree(json.writeValueAsString(pair)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accessToken", "accessExpiresAtEpochMilli", "refreshExpiresAtEpochMilli");
        assertThat(json.writeValueAsString(pair)).doesNotContain("refreshToken");
        assertThat(json.readValue(json.writeValueAsString(pair), TokenPairResponse.class)).isEqualTo(pair);
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
