package com.github.hurdadd.adapter.config;

import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import com.github.hurdadd.adapter.out.persistence.ledger.ConflictingRedeliveryException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Poison messages go straight to {@code payment-events.DLT}; transient failures (e.g. DB down) are retried
 * with exponential backoff and, once exhausted, also land in the DLT instead of being skipped.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> template) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);
        return errorHandler(template, backOff);
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<String, String> template, BackOff backOff) {
        // partition -1: the DLT need not have the same partition count as the source topic
        var recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // a redelivery that contradicts what is stored will not fix itself on retry: dead-letter it at once
        handler.addNotRetryableExceptions(UnprocessableMessageException.class, IllegalArgumentException.class,
                ConflictingRedeliveryException.class);
        // ack-mode manual: without this the offset of a dead-lettered record is never committed
        handler.setCommitRecovered(true);
        return handler;
    }
}
