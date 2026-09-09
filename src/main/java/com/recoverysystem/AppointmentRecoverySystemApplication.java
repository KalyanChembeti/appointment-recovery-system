package com.recoverysystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dynamic Appointment Recovery System.
 *
 * Modular monolith. Single clinic per MVP scope. See docs/IMPLEMENTATION_HANDBOOK.md
 * and docs/SECTION_2C_V3_COMPLETE.md for the locked Phase 0 design this codebase implements.
 */
@SpringBootApplication
@EnableScheduling
public class AppointmentRecoverySystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppointmentRecoverySystemApplication.class, args);
    }
}
