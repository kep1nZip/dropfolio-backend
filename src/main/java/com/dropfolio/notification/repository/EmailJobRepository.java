package com.dropfolio.notification.repository;

import com.dropfolio.notification.entity.EmailJob;
import com.dropfolio.notification.entity.EmailJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailJobRepository extends JpaRepository<EmailJob, Long> {

    /** ERD.md §2.12 — exactly {@code EmailWorker}'s polling query, oldest-first, batched via {@code Pageable}. */
    List<EmailJob> findByStatusOrderByCreatedAtAsc(EmailJobStatus status, Pageable pageable);
}
