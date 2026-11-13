package dev.ocaina.tendwise.handoff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/handoffs")
public class HandoffController {

  private final HandoffService handoffService;

  public HandoffController(HandoffService handoffService) {
    this.handoffService = handoffService;
  }

  /** 201 with a Location header. 404 if the business does not exist, 400 if the body is invalid. */
  @PostMapping
  public ResponseEntity<HandoffResponse> create(@Valid @RequestBody CreateHandoffRequest request) {
    HandoffResponse created = handoffService.create(request);
    return ResponseEntity.created(URI.create("/api/v1/handoffs/" + created.id())).body(created);
  }

  /** Handoffs of one business, oldest first. Without status, all statuses are returned. */
  @GetMapping
  public List<HandoffResponse> list(
      @RequestParam @NotBlank String businessSlug,
      @RequestParam(required = false) HandoffStatus status) {
    return handoffService.list(businessSlug, status);
  }

  /** Any status can change to any other (no state machine in the MVP). */
  @PatchMapping("/{id}")
  public HandoffResponse updateStatus(
      @PathVariable Long id,
      @RequestParam @NotBlank String businessSlug,
      @Valid @RequestBody UpdateHandoffStatusRequest request) {
    return handoffService.updateStatus(businessSlug, id, request.status());
  }
}
