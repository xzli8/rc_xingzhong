package org.example.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.notification.model.DeliveryResult;
import org.example.notification.model.DeliveryTask;
import org.example.notification.model.NotificationRequest;
import org.example.notification.model.TaskStatus;
import org.example.notification.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通知业务服务：接收、查询、重试、调度投递
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final TaskRepository taskRepository;
    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.retry-interval-ms:10000}")
    private long retryIntervalMs;

    @Value("${scheduler.max-retries:3}")
    private int defaultMaxRetries;

    public String submit(NotificationRequest request) {
        String taskId = UUID.randomUUID().toString();

        DeliveryTask task = DeliveryTask.builder()
                .taskId(taskId)
                .bizKey(request.getBizKey())
                .targetUrl(request.getTargetUrl())
                .httpMethod(request.getHttpMethod())
                .headersJson(toHeadersJson(request.getHeaders()))
                .body(request.getBody())
                .status(TaskStatus.PENDING)
                .maxRetries(defaultMaxRetries)
                .build();
        taskRepository.save(task);

        log.info("任务提交成功: taskId={}, bizKey={}", taskId, request.getBizKey());
        return taskId;
    }

    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:5000}")
    public void processDelivery() {
        List<DeliveryTask> pendingTasks = taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);
        for (DeliveryTask task : pendingTasks) {
            processSingleTask(task);
        }

        List<DeliveryTask> retryTasks = taskRepository.findRetryableTasks(TaskStatus.RETRY_WAIT, LocalDateTime.now());
        for (DeliveryTask task : retryTasks) {
            processSingleTask(task);
        }
    }

    public DeliveryTask getTask(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    public List<DeliveryTask> getTasksByBizKey(String bizKey) {
        return taskRepository.findByBizKeyOrderByCreatedAtAsc(bizKey);
    }

    public List<DeliveryTask> getDeadLetters() {
        return taskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.DEAD_LETTER);
    }

    public boolean retryDeadLetter(String taskId) {
        DeliveryTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.DEAD_LETTER) {
            return false;
        }
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setErrorMessage(null);
        task.setNextRetryAt(null);
        taskRepository.save(task);
        log.info("死信重投成功: taskId={}", taskId);
        return true;
    }

    private void processSingleTask(DeliveryTask task) {
        task.setStatus(TaskStatus.DELIVERING);
        taskRepository.save(task);

        NotificationRequest request = toNotificationRequest(task);
        DeliveryResult result = deliveryService.execute(task.getTaskId(), request);

        if (result.isSuccess()) {
            handleSuccess(task);
            return;
        }
        handleFailure(task, result.getErrorMessage());
    }

    private void handleSuccess(DeliveryTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setErrorMessage(null);
        task.setNextRetryAt(null);
        taskRepository.save(task);
        log.info("任务投递成功: taskId={}", task.getTaskId());
    }

    private void handleFailure(DeliveryTask task, String errorMessage) {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(errorMessage);

        if (task.getRetryCount() >= task.getMaxRetries()) {
            task.setStatus(TaskStatus.DEAD_LETTER);
            task.setNextRetryAt(null);
            taskRepository.save(task);
            log.warn("任务进入死信: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
            return;
        }

        task.setStatus(TaskStatus.RETRY_WAIT);
        task.setNextRetryAt(LocalDateTime.now().plus(Duration.ofMillis(retryIntervalMs)));
        taskRepository.save(task);
        log.info("任务进入重试等待: taskId={}, retryCount={}/{}", task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
    }

    private NotificationRequest toNotificationRequest(DeliveryTask task) {
        return NotificationRequest.builder()
                .bizKey(task.getBizKey())
                .targetUrl(task.getTargetUrl())
                .httpMethod(task.getHttpMethod())
                .headers(fromHeadersJson(task.getHeadersJson()))
                .body(task.getBody())
                .build();
    }

    private String toHeadersJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers == null ? Collections.<String, String>emptyMap() : headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("headers序列化失败", e);
        }
    }

    private Map<String, String> fromHeadersJson(String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("headers反序列化失败，降级为空map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
