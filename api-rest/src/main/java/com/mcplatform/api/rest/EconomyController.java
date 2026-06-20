package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.EconomyMapper;
import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST surface for economy operations (web interface / admin). The first real vertical slice. */
@RestController
@RequestMapping("/api/players/{uuid}/balances/{currency}")
public class EconomyController {

    private final EconomyService economy;

    public EconomyController(EconomyService economy) {
        this.economy = economy;
    }

    @GetMapping
    public BalanceResponse balance(@PathVariable UUID uuid, @PathVariable String currency) {
        return EconomyMapper.balanceResponse(economy.balance(PlayerId.of(uuid), CurrencyCode.of(currency)));
    }

    @PostMapping("/credit")
    public BalanceResponse credit(@PathVariable UUID uuid, @PathVariable String currency,
            @RequestBody AmountRequest request) {
        return EconomyMapper.balanceResponse(economy.credit(PlayerId.of(uuid), CurrencyCode.of(currency),
                Money.of(request.amount()), EconomyMapper.transactionId(request),
                EconomyMapper.sourceOr(request.source(), "WEB")));
    }

    @PostMapping("/debit")
    public BalanceResponse debit(@PathVariable UUID uuid, @PathVariable String currency,
            @RequestBody AmountRequest request) {
        return EconomyMapper.balanceResponse(economy.debit(PlayerId.of(uuid), CurrencyCode.of(currency),
                Money.of(request.amount()), EconomyMapper.transactionId(request),
                EconomyMapper.sourceOr(request.source(), "WEB")));
    }

    @PostMapping("/set")
    public BalanceResponse set(@PathVariable UUID uuid, @PathVariable String currency,
            @RequestBody AmountRequest request) {
        return EconomyMapper.balanceResponse(economy.set(PlayerId.of(uuid), CurrencyCode.of(currency),
                Money.of(request.amount()), EconomyMapper.transactionId(request),
                EconomyMapper.sourceOr(request.source(), "WEB:admin")));
    }

    /** Transfer from {@code uuid} (path) to {@code request.to()}, both in {@code currency}. */
    @PostMapping("/transfer")
    public TransferResponse transfer(@PathVariable UUID uuid, @PathVariable String currency,
            @RequestBody TransferRequest request) {
        return EconomyMapper.transferResponse(economy.transfer(
                PlayerId.of(uuid), PlayerId.of(request.to()), CurrencyCode.of(currency),
                Money.of(request.amount()), EconomyMapper.correlationId(request),
                EconomyMapper.sourceOr(request.source(), "WEB:transfer")));
    }
}
