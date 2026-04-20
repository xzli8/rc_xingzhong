package org.example.notification.intake;

import lombok.RequiredArgsConstructor;
import org.example.notification.model.NotificationRequest;
import org.example.notification.scheduler.DispatchScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 接入模块 - HTTP接口接收业务系统通知请求
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class IntakeController {

    private final DispatchScheduler dispatchScheduler;

    /**
     * 接收通知请求
     * 校验必填字段，转发至调度模块
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody NotificationRequest request) {
        // 基础校验 - 仅判断存在性
        if (request.getBizKey() == null || request.getBizKey().trim().isEmpty()) {
            return badRequest("bizKey不能为空");
        }
        if (request.getTargetUrl() == null || request.getTargetUrl().trim().isEmpty()) {
            return badRequest("targetUrl不能为空");
        }
        if (request.getHttpMethod() == null || request.getHttpMethod().trim().isEmpty()) {
            request.setHttpMethod("POST");
        }

        // 转发至调度模块
        String taskId = dispatchScheduler.submit(request);

        Map<String, String> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("message", "请求已接收");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return ResponseEntity.badRequest().body(error);
    }
}
