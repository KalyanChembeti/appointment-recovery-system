package com.recoverysystem.service;

import java.time.Instant;

public record TimeSlot(Instant startAt, Instant endAt) {
}
