package com.mcplatform.protocol.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.EconomyEndpoints;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionEndpoints;
import com.mcplatform.protocol.session.SessionJoinResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EndpointDescriptorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void expandsPathTemplateLeftToRight() {
        assertThat(EconomyEndpoints.GET_BALANCE.expand(PLAYER, "COINS"))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/balances/COINS");
        assertThat(EconomyEndpoints.CREDIT.expand(PLAYER, "COINS"))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/balances/COINS/credit");
        assertThat(EconomyEndpoints.TRANSFER.expand(PLAYER, "COINS"))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/balances/COINS/transfer");
        assertThat(SessionEndpoints.JOIN.expand(PLAYER))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/session/join");
        assertThat(SessionEndpoints.UPSERT_PLAYER.expand(PLAYER))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001");
    }

    @Test
    void carriesMethodAndPayloadTypes() {
        assertThat(EconomyEndpoints.GET_BALANCE.method()).isEqualTo(HttpMethod.GET);
        assertThat(EconomyEndpoints.GET_BALANCE.requestType()).isEqualTo(Void.class);
        assertThat(EconomyEndpoints.GET_BALANCE.responseType()).isEqualTo(BalanceResponse.class);

        assertThat(EconomyEndpoints.CREDIT.method()).isEqualTo(HttpMethod.POST);
        assertThat(EconomyEndpoints.CREDIT.requestType()).isEqualTo(AmountRequest.class);

        assertThat(EconomyEndpoints.TRANSFER.requestType()).isEqualTo(TransferRequest.class);
        assertThat(EconomyEndpoints.TRANSFER.responseType()).isEqualTo(TransferResponse.class);

        assertThat(SessionEndpoints.UPSERT_PLAYER.method()).isEqualTo(HttpMethod.PUT);
        assertThat(SessionEndpoints.UPSERT_PLAYER.requestType()).isEqualTo(PlayerRequest.class);
        assertThat(SessionEndpoints.UPSERT_PLAYER.responseType()).isEqualTo(Void.class);

        assertThat(SessionEndpoints.JOIN.responseType()).isEqualTo(SessionJoinResponse.class);
    }

    @Test
    void rejectsWrongPathVarCountAndBadTemplate() {
        assertThatThrownBy(() -> EconomyEndpoints.GET_BALANCE.expand(PLAYER))
                .isInstanceOf(IllegalArgumentException.class); // too few
        assertThatThrownBy(() -> SessionEndpoints.JOIN.expand(PLAYER, "extra"))
                .isInstanceOf(IllegalArgumentException.class); // too many
        assertThatThrownBy(() -> new EndpointDescriptor<>(HttpMethod.GET, "no-leading-slash", Void.class, Void.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
