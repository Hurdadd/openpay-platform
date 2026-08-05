package com.github.hurdadd.payment.model;


public record Duplicate(StoredResponse response) implements IdempotencyResult {
}
