package dev.ocaina.tendwise.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

  long countByBusinessId(Long businessId);
}
