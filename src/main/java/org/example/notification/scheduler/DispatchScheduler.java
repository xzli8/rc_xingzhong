package org.example.notification.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.notification.executor.DeliveryExecutor;
import org.example.notification.model.*;
import org.example.notification.persistence.MessagePayload;
import org.example.notification.persistence.MessageQueue;
import org.example.notification.persistence.TaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 投递调度模块 - 核心调度中心
 * 负责任务封装、状态管理、协调各模块
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final TaskRepository taskRepository;
    private final MessageQueue messageQueue;
    private final DeliveryExecutor deliveryExecutor;

    /**
     * 提交通知请求 - 创建任务、持久化、入队
     */
    public String submit(NotificationRequest request) {
        String taskId = UUID.randomUUID().toString();

        // DB存储任务元信息
        DeliveryTask task = DeliveryTask.builder()
                .taskId(taskId)
                .bizKey(request.getBizKey())
                .targetUrl(request.getTargetUrl())
                .httpMethod(request.getHttpMethod())
                .status(TaskStatus.PENDING)
                .build();
        taskRepository.save(task);

        // MQ存储消息内容
        messageQueue.enqueue(taskId, request);

        log.info("任务提交成功: taskId={}, bizKey={}", taskId, request.getBizKey());
        return taskId;
    }

    /**
     * 定时轮询MQ，执行投递
     */
    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:5000}")
    public void processDelivery() {
        while (!messageQueue.isEmpty()) {
            MessagePayload payload = messageQueue.dequeue();
            if (payload == null) break;

            String taskId = payload.getTaskId();
            DeliveryTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) continue;

            // 更新状态为投递中
            task.setStatus(TaskStatus.DELIVERING);
            taskRepository.save(task);

            // 执行投递
            DeliveryResult result = deliveryExecutor.execute(taskId, payload.getRequest());

            if (result.isSuccess()) {
                handleSuccess(task);
            } else {
                handleFailure(task, payload.getRequest(), result.getErrorMessage());
            }
        }
    }

    private void handleSuccess(DeliveryTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        taskRepository.save(task);
        log.info("任务投递成功: taskId={}", task.getTaskId());
    }

    private void handleFailure(DeliveryTask task, NotificationRequest request, String errorMessage) {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(errorMessage);

        if (task.getRetryCount() >= task.getMaxRetries()) {
            // 重试耗尽 -> 死信
            task.setStatus(TaskStatus.DEAD_LETTER);
            taskRepository.save(task);
            log.warn("任务进入死信: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
        } else {
            // 重新入队重试
            task.setStatus(TaskStatus.RETRYING);
            taskRepository.save(task);
            messageQueue.enqueue(task.getTaskId(), request);
            log.info("任务重试: taskId={}, retryCount={}/{}", task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
        }
    }
}
