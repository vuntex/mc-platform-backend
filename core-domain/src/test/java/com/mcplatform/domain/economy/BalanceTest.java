package com.mcplatform.domain.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceTest {

    private final PlayerId player = PlayerId.of(UUID.randomUUID());
    private final CurrencyCode coins = CurrencyCode.of("COINS");
    private final TransactionId tx = TransactionId.random();

    private Balance balanceOf(long amount) {
        return new Balance(player, coins, Money.of(amount), 3);
    }

    @Test
    void creditAddsAndProducesCreditedEvent() {
        PendingEconomyEvent e = balanceOf(100).credit(Money.of(50), tx, "TEST");

        assertThat(e.type()).isEqualTo(EconomyEventType.CREDITED);
        assertThat(e.amount()).isEqualTo(Money.of(50));
        assertThat(e.balanceAfter()).isEqualTo(Money.of(150));
    }

    @Test
    void debitSubtractsWhenFundsSuffice() {
        PendingEconomyEvent e = balanceOf(100).debit(Money.of(30), tx, "TEST");

        assertThat(e.type()).isEqualTo(EconomyEventType.DEBITED);
        assertThat(e.balanceAfter()).isEqualTo(Money.of(70));
    }

    @Test
    void debitBeyondBalanceThrows() {
        assertThatThrownBy(() -> balanceOf(20).debit(Money.of(21), tx, "TEST"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void debitExactlyToZeroIsAllowed() {
        assertThat(balanceOf(20).debit(Money.of(20), tx, "TEST").balanceAfter())
                .isEqualTo(Money.of(0));
    }

    @Test
    void setOverridesToAbsoluteValue() {
        PendingEconomyEvent e = balanceOf(100).set(Money.of(5), tx, "ADMIN");

        assertThat(e.type()).isEqualTo(EconomyEventType.SET);
        assertThat(e.amount()).isEqualTo(Money.of(5));
        assertThat(e.balanceAfter()).isEqualTo(Money.of(5));
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> balanceOf(100).credit(Money.of(0), tx, "TEST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balanceOf(100).debit(Money.of(-1), tx, "TEST"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
