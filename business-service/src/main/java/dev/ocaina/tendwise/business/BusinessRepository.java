package dev.ocaina.tendwise.business;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRepository extends JpaRepository<Business, Long> {

  Optional<Business> findBySlug(String slug);

  /** The widget's start call (T38): the publishable key says which business it is for. */
  Optional<Business> findByPublishableKey(String publishableKey);
}
