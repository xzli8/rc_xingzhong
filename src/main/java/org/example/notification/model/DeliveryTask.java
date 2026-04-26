package org.example.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 投递任务实体 - DB存储元信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "delivery_task")
public class DeliveryTask {

    @Id
    private String taskId;

    @Column(nullable = false)
    private String bizKey;

    @Column(nullable = false)
    private String targetUrl;

    private String httpMethod;

    @Column(length = 4096)
    private String headersJson;

    @Column(length = 20000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Builder.Default
    private int retryCount = 0;

    @Builder.Default
    private int maxRetries = 3;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Column(length = 1024)
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
