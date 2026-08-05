package com.github.hurdadd.payment.model;

public sealed interface IdempotencyResult permits New, Duplicate, Conflict {
}
