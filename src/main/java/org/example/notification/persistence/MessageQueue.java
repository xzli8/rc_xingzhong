package org.example.notification.persistence;

import org.example.notification.model.NotificationRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 内存消息队列 - MVP阶段模拟MQ中间件
 * 存储消息具体内容，支持投递执行
 */
@Component
public class MessageQueue {

    private final ConcurrentLinkedQueue<MessagePayload> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(String taskId, NotificationRequest request) {
        queue.offer(new MessagePayload(taskId, request));
    }

    public MessagePayload dequeue() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
