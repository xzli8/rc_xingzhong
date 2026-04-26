package org.example.notification.controller;

import lombok.RequiredArgsConstructor;
import org.example.notification.model.DeliveryTask;
import org.example.notification.model.NotificationRequest;
import org.example.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知接入与查询控制器
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody NotificationRequest request) {
        if (request.getBizKey() == null || request.getBizKey().trim().isEmpty()) {
            return badRequest("bizKey不能为空");
        }
        if (request.getTargetUrl() == null || request.getTargetUrl().trim().isEmpty()) {
            return badRequest("targetUrl不能为空");
        }
        if (request.getHttpMethod() == null || request.getHttpMethod().trim().isEmpty()) {
            request.setHttpMethod("POST");
        }

        String taskId = notificationService.submit(request);
        Map<String, String> response = new HashMap<String, String>();
        response.put("taskId", taskId);
        response.put("message", "请求已接收");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        DeliveryTask task = notificationService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("task不存在"));
        }
        return ResponseEntity.ok(task);
    }

    @GetMapping
    public ResponseEntity<?> getTasksByBizKey(@RequestParam(required = false) String bizKey) {
        if (bizKey == null || bizKey.trim().isEmpty()) {
            return badRequest("bizKey不能为空");
        }
        List<DeliveryTask> tasks = notificationService.getTasksByBizKey(bizKey);
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/dead-letters")
    public ResponseEntity<List<DeliveryTask>> getDeadLetters() {
        return ResponseEntity.ok(notificationService.getDeadLetters());
    }

    @PostMapping("/dead-letters/{taskId}/retry")
    public ResponseEntity<Map<String, String>> retryDeadLetter(@PathVariable String taskId) {
        boolean success = notificationService.retryDeadLetter(taskId);
        if (!success) {
            return ResponseEntity.badRequest().body(error("task不存在或非死信状态"));
        }
        Map<String, String> response = new HashMap<String, String>();
        response.put("taskId", taskId);
        response.put("message", "重投成功");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        Map<String, String> error = new HashMap<String, String>();
        error.put("error", message);
        return ResponseEntity.badRequest().body(error);
    }

    private Map<String, String> error(String message) {
        Map<String, String> error = new HashMap<String, String>();
        error.put("error", message);
        return error;
    }

}
