package dev.ocaina.tendwise.order;

import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  private final BusinessRepository businesses;
  private final OrderRepository orders;

  public OrderService(BusinessRepository businesses, OrderRepository orders) {
    this.businesses = businesses;
    this.orders = orders;
  }

  /**
   * Returns the order status only if the order belongs to the business and the phone matches.
   *
   * <p>Unknown business, unknown code and wrong phone all throw the same exception, so a caller
   * cannot find out which order codes exist by trying phone numbers.
   */
  @Transactional(readOnly = true)
  public OrderStatusResponse getStatus(String businessSlug, String code, String phoneLast4) {
    return businesses
        .findBySlug(businessSlug)
        .flatMap(business -> orders.findByCodeAndBusinessId(code, business.getId()))
        .filter(order -> phoneEndsWith(order.getCustomerPhone(), phoneLast4))
        .map(OrderStatusResponse::from)
        .orElseThrow(() ->
            new NotFoundException(
                "Order not found", "No order matches this code and phone number."));
  }

  /** Compares only digits, so "+1 212-555-0102" ends with "0102". */
  static boolean phoneEndsWith(String phone, String last4) {
    String digits = phone.replaceAll("\\D", "");
    return digits.length() >= 4 && digits.endsWith(last4);
  }
}
