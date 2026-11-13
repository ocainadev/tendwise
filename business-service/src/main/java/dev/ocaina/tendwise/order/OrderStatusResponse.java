package dev.ocaina.tendwise.order;

import java.time.Instant;

/**
 * What the agent may tell the customer about an order. No customer name or phone (data
 * minimisation).
 */
public record OrderStatusResponse(
    String code, OrderStatus status, Fulfilment fulfilment, Instant eta, Instant updatedAt) {

  static OrderStatusResponse from(Order order) {
    return new OrderStatusResponse(
        order.getCode(),
        order.getStatus(),
        order.getFulfilment(),
        order.getEta(),
        order.getUpdatedAt());
  }
}
