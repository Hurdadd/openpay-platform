package com.github.hurdadd.application.model;


public record Duplicate(StoredResponse response) implements IdempotencyResult {
}
