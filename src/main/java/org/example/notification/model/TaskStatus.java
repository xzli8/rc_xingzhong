package org.example.notification.model;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    PENDING,      // 待投递
    DELIVERING,   // 投递中
    SUCCESS,      // 投递成功
    RETRYING,     // 重试中
    DEAD_LETTER   // 死信-重试耗尽
}
