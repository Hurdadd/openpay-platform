package com.github.hurdadd.adapter.out.persistence.idempotency;


import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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

    @EmbeddedId
    private IdempotencyId id;
    @Column(name = "body_hash", nullable = false)
    private String bodyHash;
    @Column(name = "response")
    @JdbcTypeCode(value = SqlTypes.JSON)
    @Setter
    private String response;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
