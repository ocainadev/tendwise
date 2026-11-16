package dev.ocaina.tendwise.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Reads one SSE stream over real HTTP, the way a browser does, and collects what arrives: events
 * (event, id, data lines) and comments (": heartbeat").
 */
public final class SseTestClient implements AutoCloseable {

  /** One received event. Fields not sent are null. */
  public record Event(String name, String id, String data) {}

  private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();

  /** Its own client, so close() really closes this one connection. */
  private final HttpClient http;

  private final HttpResponse<Stream<String>> response;

  private SseTestClient(HttpClient http, HttpResponse<Stream<String>> response) {
    this.http = http;
    this.response = response;
    if (response.statusCode() == 200) {
      Thread.ofVirtual().start(this::read);
    }
  }

  /** Opens the stream; returns once the response headers arrived. */
  public static SseTestClient open(String url, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream").GET();
    headers.forEach(request::header);
    HttpClient http = HttpClient.newHttpClient();
    return new SseTestClient(http, http.send(request.build(), HttpResponse.BodyHandlers.ofLines()));
  }

  public int status() {
    return response.statusCode();
  }

  /** The first body lines of a response that is not a stream (an error problem). */
  public String errorBody() {
    return String.join("\n", response.body().limit(20).toList());
  }

  /** The next event, or null when none arrives in time. */
  public Event next(Duration timeout) throws InterruptedException {
    return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The next comment line (without the ':'), or null when none arrives in time. */
  String nextComment(Duration timeout) throws InterruptedException {
    return comments.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Every event that arrives within the given time. */
  public List<Event> collect(Duration during) throws InterruptedException {
    List<Event> received = new ArrayList<>();
    long end = System.nanoTime() + during.toNanos();
    while (System.nanoTime() < end) {
      Event event = events.poll(10, TimeUnit.MILLISECONDS);
      if (event != null) {
        received.add(event);
      }
    }
    return received;
  }

  /** Closes the connection, like a browser tab that goes away. */
  @Override
  public void close() {
    response.body().close();
    // Closing the body alone may leave the TCP connection open.
    http.shutdownNow();
  }

  private void read() {
    String name = null;
    String id = null;
    StringBuilder data = new StringBuilder();
    try (Stream<String> lines = response.body()) {
      for (String line : (Iterable<String>) lines::iterator) {
        if (line.isEmpty()) {
          if (name != null || data.length() > 0) {
            events.add(new Event(name, id, data.toString()));
          }
          name = null;
          id = null;
          data.setLength(0);
        } else if (line.startsWith(":")) {
          comments.add(line.substring(1).strip());
        } else if (line.startsWith("event:")) {
          name = line.substring("event:".length()).strip();
        } else if (line.startsWith("id:")) {
          id = line.substring("id:".length()).strip();
        } else if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()).strip());
        }
      }
    } catch (RuntimeException e) {
      // The connection was closed (close() or the server ended the stream).
    }
  }
}
