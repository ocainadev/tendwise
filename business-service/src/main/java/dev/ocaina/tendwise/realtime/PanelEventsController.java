package dev.ocaina.tendwise.realtime;

import dev.ocaina.tendwise.panel.CurrentMembership;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The panel's live stream: every event of the business (docs/architecture.md, "Real-time"). Any
 * member (OWNER or AGENT). PanelGuard has already checked the slug, so a user of another business
 * gets 404 before a stream is opened.
 */
@RestController
class PanelEventsController {

  private final EventStreams streams;

  PanelEventsController(EventStreams streams) {
    this.streams = streams;
  }

  /**
   * Last-Event-ID: sent by the browser when it reconnects. The panel answers with a resync hint.
   */
  @GetMapping(value = "/api/v1/panel/{slug}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(
      CurrentMembership membership,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return streams.openPanelStream(membership.businessId(), lastEventId != null);
  }
}
