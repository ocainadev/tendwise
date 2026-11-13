package dev.ocaina.tendwise.business;

import dev.ocaina.tendwise.catalog.CatalogItem;
import dev.ocaina.tendwise.catalog.CatalogItemKind;
import java.math.BigDecimal;

/**
 * One catalog item. Unavailable items are included with available=false, so the agent can say "out
 * of stock" instead of pretending the item does not exist.
 */
public record CatalogItemResponse(
    Long id,
    String name,
    String description,
    BigDecimal price,
    String currency,
    boolean available,
    CatalogItemKind kind) {

  static CatalogItemResponse from(CatalogItem item) {
    return new CatalogItemResponse(
        item.getId(),
        item.getName(),
        item.getDescription(),
        item.getPrice(),
        item.getCurrency(),
        item.isAvailable(),
        item.getKind());
  }
}
