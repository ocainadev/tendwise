package dev.ocaina.tendwise.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Status and ETA of an order. 404 if the code does not exist in this business or the phone does
   * not match, without telling the cases apart. 400 if phoneLast4 is not exactly 4 digits.
   */
  @GetMapping("/{code}/status")
  public OrderStatusResponse getStatus(
      @PathVariable String code,
      @RequestParam @NotBlank String businessSlug,
      @RequestParam @Pattern(regexp = "\\d{4}") String phoneLast4) {
    return orderService.getStatus(businessSlug, code, phoneLast4);
  }
}
