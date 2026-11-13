package dev.ocaina.tendwise.handoff;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.shared.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffService {

  private final BusinessRepository businesses;
  private final HandoffRepository handoffs;

  public HandoffService(BusinessRepository businesses, HandoffRepository handoffs) {
    this.businesses = businesses;
    this.handoffs = handoffs;
  }

  /** Creates an OPEN handoff for the business. 404 if the business does not exist. */
  @Transactional
  public HandoffResponse create(CreateHandoffRequest request) {
    Business business = findBusiness(request.businessSlug());
    Handoff handoff =
        new Handoff(
            business.getId(), request.conversationId(), request.reason(), request.summary());
    return HandoffResponse.from(handoffs.save(handoff));
  }

  /** Handoffs of the business, oldest first. A null status means "all statuses". */
  @Transactional(readOnly = true)
  public List<HandoffResponse> list(String businessSlug, HandoffStatus status) {
    Long businessId = findBusiness(businessSlug).getId();
    List<Handoff> found =
        status == null
            ? handoffs.findByBusinessIdOrderByCreatedAtAsc(businessId)
            : handoffs.findByBusinessIdAndStatusOrderByCreatedAtAsc(businessId, status);
    return found.stream().map(HandoffResponse::from).toList();
  }

  /**
   * Changes the status. Unknown business, unknown id and an id of another business all throw the
   * same exception, so a caller cannot probe which ids exist elsewhere.
   */
  @Transactional
  public HandoffResponse updateStatus(String businessSlug, Long id, HandoffStatus status) {
    Handoff handoff =
        businesses
            .findBySlug(businessSlug)
            .flatMap(business -> handoffs.findByIdAndBusinessId(id, business.getId()))
            .orElseThrow(() ->
                new NotFoundException(
                    "Handoff not found", "No handoff with this id in this business."));
    // The entity is managed, so the change is saved when the transaction commits.
    handoff.changeStatus(status);
    return HandoffResponse.from(handoff);
  }

  private Business findBusiness(String slug) {
    return businesses
        .findBySlug(slug)
        .orElseThrow(
            () -> new NotFoundException("Business not found", "No business with this slug."));
  }
}
