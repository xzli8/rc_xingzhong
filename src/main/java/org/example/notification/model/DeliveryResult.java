package org.example.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投递执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryResult {
    private boolean success;
    private int httpStatusCode;
    private String errorMessage;
}
