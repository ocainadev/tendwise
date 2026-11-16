package dev.ocaina.tendwise.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: only save and reads. Every read takes a businessId. */
public interface AuditLogRepository extends JpaRepository<AuditEntry, Long> {

  /** The entries of one record of a business, oldest first. */
  List<AuditEntry> findByBusinessIdAndTargetTypeAndTargetIdOrderByIdAsc(
      Long businessId, String targetType, String targetId);
}
