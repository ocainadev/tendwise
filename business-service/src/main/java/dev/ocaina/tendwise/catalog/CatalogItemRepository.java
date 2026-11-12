package dev.ocaina.tendwise.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

  List<CatalogItem> findByBusinessIdOrderByNameAsc(Long businessId);
}
