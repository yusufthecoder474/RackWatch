package com.rackwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** RackWatch API entry point. No security auto-configuration is used on
 * purpose: API-key auth is a plain servlet filter (see security package),
 * which is the whole auth model for this project. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RackwatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(RackwatchApplication.class, args);
    }
}
