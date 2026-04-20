package org.example.notification.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.notification.model.NotificationRequest;

/**
 * MQ消息载体 - 关联taskId与请求内容
 */
@Data
@AllArgsConstructor
public class MessagePayload {
    private String taskId;
    private NotificationRequest request;
}
