package dev.ocaina.tendwise.handoff;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query takes a businessId, so one business never sees another's handoffs. */
public interface HandoffRepository extends JpaRepository<Handoff, Long> {

  List<Handoff> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

  List<Handoff> findByBusinessIdAndStatusOrderByCreatedAtAsc(Long businessId, HandoffStatus status);

  Optional<Handoff> findByIdAndBusinessId(Long id, Long businessId);
}
