package dev.ocaina.tendwise.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A product or service a business sells. */
@Entity
@Table(name = "catalog_items")
public class CatalogItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Plain id instead of a JPA relation: queries always filter by it, and nothing is lazy. */
  private Long businessId;

  private String name;

  private String description;

  private BigDecimal price;

  /** ISO 4217 code, e.g. USD or BRL. */
  private String currency;

  private boolean available;

  @Enumerated(EnumType.STRING)
  private CatalogItemKind kind;

  /** Required by JPA. */
  protected CatalogItem() {}

  public Long getId() {
    return id;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getCurrency() {
    return currency;
  }

  public boolean isAvailable() {
    return available;
  }

  public CatalogItemKind getKind() {
    return kind;
  }
}
