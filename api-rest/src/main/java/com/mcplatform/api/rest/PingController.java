package com.mcplatform.api.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness ping — skeleton wiring only, no business logic yet.
 * Confirms the api-rest module is component-scanned and the web layer is up.
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public String ping() {
        return "pong";
    }
}
