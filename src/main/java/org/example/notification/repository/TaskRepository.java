package org.example.notification.repository;

import org.example.notification.model.DeliveryTask;
import org.example.notification.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务持久化仓库
 */
public interface TaskRepository extends JpaRepository<DeliveryTask, String> {

    List<DeliveryTask> findByStatusOrderByCreatedAtAsc(TaskStatus status);

    List<DeliveryTask> findByBizKeyOrderByCreatedAtAsc(String bizKey);

    @Query("SELECT t FROM DeliveryTask t WHERE t.status = :status AND t.nextRetryAt IS NOT NULL AND t.nextRetryAt <= :now ORDER BY t.nextRetryAt ASC")
    List<DeliveryTask> findRetryableTasks(@Param("status") TaskStatus status, @Param("now") LocalDateTime now);
}
