package com.mcplatform.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap entry point. Scans the whole {@code com.mcplatform} package tree so the
 * controllers in api-rest / api-realtime and any future adapters are picked up.
 */
@SpringBootApplication(scanBasePackages = "com.mcplatform")
public class McPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(McPlatformApplication.class, args);
    }
}
