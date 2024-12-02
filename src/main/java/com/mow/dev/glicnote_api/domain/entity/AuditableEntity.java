package com.mow.dev.glicnote_api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;


@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @Version
    @Column(name = "_version")
    private Integer version;

    @CreationTimestamp
    @Column(name = "_created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "_updated_at")
    private OffsetDateTime updatedAt;

    @CreatedBy
    @Column(name = "_created_by")
    private String createdBy = "SYSTEM";

    @LastModifiedBy
    @Column(name = "_updated_by")
    private String updatedBy = "SYSTEM";
}

