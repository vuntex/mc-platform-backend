package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.economy.EconomyAlertMonitor;
import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.domain.economy.AppliedEconomyEvent;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Decorates the real {@link BalanceEventPublisher}: after publishing the balance event as usual, it runs
 * the {@link EconomyAlertMonitor} on the same applied event. Keeps alert detection out of the core
 * {@code EconomyService} (no change there) while still seeing every movement. Alert evaluation never
 * affects publishing — any failure is logged and swallowed.
 */
public final class AlertingBalanceEventPublisher implements BalanceEventPublisher {

    private static final Logger LOG = System.getLogger(AlertingBalanceEventPublisher.class.getName());

    private final BalanceEventPublisher delegate;
    private final EconomyAlertMonitor monitor;

    public AlertingBalanceEventPublisher(BalanceEventPublisher delegate, EconomyAlertMonitor monitor) {
        this.delegate = delegate;
        this.monitor = monitor;
    }

    @Override
    public void publish(AppliedEconomyEvent event) {
        delegate.publish(event);
        try {
            monitor.evaluate(event);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "economy alert evaluation failed", ex);
        }
    }
}
