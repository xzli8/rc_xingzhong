package org.example.notification.persistence;

import org.example.notification.model.DeliveryTask;
import org.example.notification.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 任务持久化仓库
 */
public interface TaskRepository extends JpaRepository<DeliveryTask, String> {
    List<DeliveryTask> findByStatus(TaskStatus status);
    List<DeliveryTask> findByBizKey(String bizKey);
}
