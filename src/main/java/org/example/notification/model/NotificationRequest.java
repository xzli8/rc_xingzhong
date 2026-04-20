package org.example.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 业务系统提交的通知请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {
    private String bizKey;       // 业务唯一标识
    private String targetUrl;    // 目标投递地址
    @Builder.Default
    private String httpMethod = "POST";  // HTTP方法
    private Map<String, String> headers; // 自定义请求头
    private String body;         // 请求体内容
}
