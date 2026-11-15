package dev.ocaina.tendwise.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public conversation API for the website chat widget (docs/architecture.md, "Public API"). No
 * cookies and no CSRF: the publishable key starts a conversation, then the conversation token in
 * the Authorization header (never in the URL) identifies it.
 */
@RestController
@RequestMapping("/public/v1/conversations")
class PublicConversationController {

  private final PublicConversationService service;

  PublicConversationController(PublicConversationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  StartConversationResponse start(
      @Valid @RequestBody StartConversationRequest request,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
      HttpServletRequest http) {
    return service.start(
        request.publishableKey(), request.language(), origin, http.getRemoteAddr());
  }

  /** 202: the message is stored; the reply (AI or staff) arrives later. */
  @PostMapping("/{id}/messages")
  @ResponseStatus(HttpStatus.ACCEPTED)
  PublicMessage send(
      @PathVariable UUID id,
      @Valid @RequestBody SendMessageRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
      HttpServletRequest http) {
    return service.send(id, authorization, request.body(), origin, http.getRemoteAddr());
  }

  /** after: the newest message id the widget already has (0 or absent = from the start). */
  @GetMapping("/{id}/messages")
  List<PublicMessage> history(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
    return service.history(id, authorization, after, origin);
  }

  @ExceptionHandler(OriginNotAllowedException.class)
  ProblemDetail handleOriginNotAllowed(OriginNotAllowedException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    problem.setTitle("Origin not allowed");
    return problem;
  }

  @ExceptionHandler(RateLimitExceededException.class)
  ProblemDetail handleRateLimit(RateLimitExceededException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    problem.setTitle("Too many requests");
    return problem;
  }
}
