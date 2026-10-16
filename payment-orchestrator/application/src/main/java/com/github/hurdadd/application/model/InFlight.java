package com.github.hurdadd.application.model;

/** The key exists but its first request has not stored a response yet. */
public record InFlight() implements IdempotencyResult {
}
