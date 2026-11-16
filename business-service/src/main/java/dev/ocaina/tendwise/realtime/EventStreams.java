package dev.ocaina.tendwise.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * The SSE streams open on this instance, grouped by business (docs/architecture.md, "Real-time").
 *
 * <ul>
 *   <li>A <b>panel</b> stream belongs to one business and gets all of its events.
 *   <li>A <b>widget</b> stream belongs to one conversation and gets only that conversation's events
 *       meant for the customer ({@link EventType#forWidget()}), never internal messages.
 * </ul>
 *
 * <p>Every event names its business, and only that business's streams are even looked at, so one
 * business's events never reach another business's stream.
 *
 * <p>What a client receives: {@code event: <type>} and {@code data: {"type", "conversationId",
 * "id", "state"}} (ids only). A message.created event also has {@code id: <message id>}, so after a
 * reconnect the browser sends the newest message id it has as {@code Last-Event-ID}. A {@code
 * resync} event means "events may have been missed: refetch".
 */
@Component
public class EventStreams {

  /** Name of the hint that tells a client to refetch (it may have missed events). */
  static final String RESYNC = "resync";

  private final Map<Long, Set<Subscriber>> byBusiness = new ConcurrentHashMap<>();
  private final Duration streamTimeout;

  EventStreams(@Value("${tendwise.realtime.stream-timeout}") Duration streamTimeout) {
    this.streamTimeout = streamTimeout;
  }

  /**
   * One open stream. conversationId is null for a panel stream (the whole business).
   *
   * <p>A widget stream gets an event only when it is about its conversation, is a widget type, and
   * is not an internal message.
   */
  record Subscriber(long businessId, UUID conversationId, SseEmitter emitter) {
    boolean wants(RealtimeEvent event) {
      if (conversationId == null) {
        return true;
      }
      return conversationId.equals(event.conversationId())
          && event.type().forWidget()
          && !event.internal();
    }
  }

  /** What the browser gets as the event's data: ids only. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ClientEvent(String type, UUID conversationId, Long id, String state) {}

  /**
   * Opens the panel stream of a business. resuming: the browser reconnected (it sent
   * Last-Event-ID). The panel stream does not replay, so it gets a resync hint and refetches.
   */
  public SseEmitter openPanelStream(long businessId, boolean resuming) {
    Subscriber subscriber = open(businessId, null);
    if (resuming) {
      send(subscriber, resync());
    }
    return subscriber.emitter();
  }

  /** Opens the widget stream of one conversation and first sends the missed events, in order. */
  public SseEmitter openWidgetStream(
      long businessId, UUID conversationId, List<RealtimeEvent> missed) {
    Subscriber subscriber = open(businessId, conversationId);
    for (RealtimeEvent event : missed) {
      send(subscriber, toSse(event));
    }
    return subscriber.emitter();
  }

  /** Sends an event (from {@link NotificationListener}) to this business's streams that want it. */
  public void deliver(RealtimeEvent event) {
    for (Subscriber subscriber : byBusiness.getOrDefault(event.businessId(), Set.of())) {
      if (subscriber.wants(event)) {
        send(subscriber, toSse(event));
      }
    }
  }

  /** Every open stream may have missed events (the listener was disconnected). */
  void resyncAll() {
    forEachStream(subscriber -> send(subscriber, resync()));
  }

  /**
   * A comment line (": heartbeat") every 25 seconds keeps proxies from closing an idle stream and
   * shows that a client has gone (the write fails).
   */
  @Scheduled(
      initialDelayString = "${tendwise.realtime.heartbeat-interval}",
      fixedRateString = "${tendwise.realtime.heartbeat-interval}")
  void sendHeartbeats() {
    forEachStream(subscriber -> send(subscriber, SseEmitter.event().comment("heartbeat")));
  }

  /**
   * Ends every stream (the instance is shutting down). Without this, Spring Boot's graceful
   * shutdown would wait for the open streams until its timeout. Browsers reconnect to another
   * instance.
   */
  void closeAll() {
    forEachStream(subscriber -> {
      remove(subscriber);
      subscriber.emitter().complete();
    });
  }

  /** Streams open on this instance (for tests). */
  int openStreams() {
    return byBusiness.values().stream().mapToInt(Set::size).sum();
  }

  private Subscriber open(long businessId, UUID conversationId) {
    // After the timeout the stream ends; the browser reconnects with Last-Event-ID.
    SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
    Subscriber subscriber = new Subscriber(businessId, conversationId, emitter);
    // Called when the client disconnects, the stream times out or a write fails.
    emitter.onCompletion(() -> remove(subscriber));
    emitter.onTimeout(() -> remove(subscriber));
    emitter.onError(error -> remove(subscriber));
    byBusiness.compute(
        businessId,
        (id, streams) -> {
          Set<Subscriber> set = streams != null ? streams : ConcurrentHashMap.newKeySet();
          set.add(subscriber);
          return set;
        });
    // Sends the response headers now, so the browser knows the stream is open.
    send(subscriber, SseEmitter.event().comment("connected"));
    return subscriber;
  }

  private void remove(Subscriber subscriber) {
    byBusiness.computeIfPresent(
        subscriber.businessId(),
        (id, streams) -> {
          streams.remove(subscriber);
          return streams.isEmpty() ? null : streams;
        });
  }

  private void forEachStream(Consumer<Subscriber> action) {
    byBusiness.values().forEach(streams -> streams.forEach(action));
  }

  /**
   * A failed write means the client is gone: the stream is forgotten. The servlet container then
   * reports the error to Spring MVC, which cleans the request up (see ResponseBodyEmitter#send).
   */
  private void send(Subscriber subscriber, SseEventBuilder event) {
    try {
      subscriber.emitter().send(event);
    } catch (IOException | IllegalStateException e) {
      // IllegalStateException: the stream had already ended.
      remove(subscriber);
    }
  }

  private static SseEventBuilder toSse(RealtimeEvent event) {
    SseEventBuilder sse =
        SseEmitter.event()
            .name(event.type().wireName())
            .data(
                new ClientEvent(
                    event.type().wireName(), event.conversationId(), event.id(), event.state()),
                MediaType.APPLICATION_JSON);
    if (event.type() == EventType.MESSAGE_CREATED) {
      sse.id(String.valueOf(event.id()));
    }
    return sse;
  }

  private static SseEventBuilder resync() {
    return SseEmitter.event()
        .name(RESYNC)
        .data(new ClientEvent(RESYNC, null, null, null), MediaType.APPLICATION_JSON);
  }
}
