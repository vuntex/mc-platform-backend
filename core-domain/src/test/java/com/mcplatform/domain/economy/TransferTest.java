package com.mcplatform.domain.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

    private final CurrencyCode coins = CurrencyCode.of("COINS");
    private final PlayerId alice = PlayerId.of(UUID.randomUUID());
    private final PlayerId bob = PlayerId.of(UUID.randomUUID());
    private final TransferId correlation = TransferId.random();

    private Balance balance(PlayerId p, long amount) {
        return new Balance(p, coins, Money.of(amount), 1);
    }

    @Test
    void producesOutAndInLegsSharingCorrelation() {
        TransferEvents events = Transfer.prepare(balance(alice, 100), balance(bob, 10),
                Money.of(30), correlation, "WEB:transfer");

        assertThat(events.out().type()).isEqualTo(EconomyEventType.TRANSFER_OUT);
        assertThat(events.out().balanceAfter()).isEqualTo(Money.of(70));
        assertThat(events.in().type()).isEqualTo(EconomyEventType.TRANSFER_IN);
        assertThat(events.in().balanceAfter()).isEqualTo(Money.of(40));

        assertThat(events.out().correlationId()).isEqualTo(correlation);
        assertThat(events.in().correlationId()).isEqualTo(correlation);
    }

    @Test
    void legsCarryDeterministicDistinctTransactionIds() {
        TransferEvents events = Transfer.prepare(balance(alice, 100), balance(bob, 0),
                Money.of(5), correlation, "WEB:transfer");

        assertThat(events.out().transactionId()).isEqualTo(correlation.outboundTransactionId());
        assertThat(events.in().transactionId()).isEqualTo(correlation.inboundTransactionId());
        assertThat(events.out().transactionId()).isNotEqualTo(events.in().transactionId());
        // deterministic: same correlation id always derives the same keys
        assertThat(correlation.outboundTransactionId()).isEqualTo(correlation.outboundTransactionId());
    }

    @Test
    void rejectsInsufficientFunds() {
        assertThatThrownBy(() -> Transfer.prepare(balance(alice, 10), balance(bob, 0),
                Money.of(11), correlation, "WEB:transfer"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void rejectsSelfTransferAndCurrencyMismatch() {
        assertThatThrownBy(() -> Transfer.prepare(balance(alice, 50), balance(alice, 50),
                Money.of(5), correlation, "WEB:transfer"))
                .isInstanceOf(IllegalArgumentException.class);

        Balance gems = new Balance(bob, CurrencyCode.of("GEMS"), Money.of(0), 1);
        assertThatThrownBy(() -> Transfer.prepare(balance(alice, 50), gems,
                Money.of(5), correlation, "WEB:transfer"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
