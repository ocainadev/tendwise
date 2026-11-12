package dev.ocaina.tendwise.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A customer order. Read-only for the agent: no tool changes orders. */
@Entity
@Table(name = "orders")
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Public code the customer knows, e.g. JP-1042. Unique across all businesses. */
  private String code;

  private Long businessId;

  private String customerName;

  /** Personal data: never log it in full (mask as ****1234). */
  private String customerPhone;

  @Enumerated(EnumType.STRING)
  private Fulfilment fulfilment;

  @Enumerated(EnumType.STRING)
  private OrderStatus status;

  /** Estimated time the order is ready or delivered. Null if unknown. */
  private Instant eta;

  /** Set by the database default. */
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  private Instant updatedAt;

  /** Required by JPA. */
  protected Order() {}

  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerPhone() {
    return customerPhone;
  }

  public Fulfilment getFulfilment() {
    return fulfilment;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Instant getEta() {
    return eta;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
