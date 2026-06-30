package com.mcplatform.application.economy.port;

import com.mcplatform.application.economy.EconomyAlert;

/** Outbound port: publish an economy alert (implemented by a Redis adapter in the app module). */
public interface EconomyAlertPublisher {

    void publish(EconomyAlert alert);
}
