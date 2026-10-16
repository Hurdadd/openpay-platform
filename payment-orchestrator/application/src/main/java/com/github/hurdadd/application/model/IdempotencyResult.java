package com.github.hurdadd.application.model;

public sealed interface IdempotencyResult permits New, Duplicate, Conflict, InFlight {
}
