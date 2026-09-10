package com.recoverysystem.service;

import java.time.Instant;

public record ResolvedBooking(Long appointmentTypeId, Instant startAt, Instant endAt) {
}
