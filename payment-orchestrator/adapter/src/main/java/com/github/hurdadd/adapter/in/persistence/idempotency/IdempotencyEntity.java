package com.github.hurdadd.adapter.in.persistence.idempotency;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class IdempotencyEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(name = "body_hash", nullable = false)
    private String bodyHash;
    @Column(name = "response")
    @JdbcTypeCode(value = SqlTypes.JSON)
    @Setter
    private String response;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
