package dev.ocaina.tendwise.job;

import org.springframework.data.jpa.repository.JpaRepository;

/** The queue is platform-wide (no business_id column); the worker (T39) adds its queries here. */
public interface JobRepository extends JpaRepository<Job, Long> {}
