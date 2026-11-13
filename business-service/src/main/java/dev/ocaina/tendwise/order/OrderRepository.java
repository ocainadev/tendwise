package dev.ocaina.tendwise.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

  long countByBusinessId(Long businessId);

  Optional<Order> findByCodeAndBusinessId(String code, Long businessId);
}
