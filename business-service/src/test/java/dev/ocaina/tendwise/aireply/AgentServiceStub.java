package dev.ocaina.tendwise.aireply;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake agent-service POST /internal/v1/replies on a random local port, built on the JDK's own
 * HTTP server (no test dependency). Tests queue the answers it gives and read back the requests it
 * got. With nothing queued it answers {@link #OK_REPLY}.
 */
class AgentServiceStub {

  static final String OK_REPLY =
      """
      {"reply": "We open at 9.", "intent": "faq", "language": "en",
       "sources": [{"title": "Hours", "source_path": "joes-pizza/hours.md", "section": "Opening"}],
       "handoff": false, "order_code": null}
      """;

  /** One answer: an HTTP status, a JSON body, and something to do before answering. */
  record Answer(int status, String body, Runnable beforeAnswer) {}

  /** One request received: its X-Api-Key header and its JSON body. */
  record Received(String apiKey, String body) {}

  private final HttpServer server;
  private final Deque<Answer> answers = new ArrayDeque<>();
  private final List<Received> received = new CopyOnWriteArrayList<>();

  AgentServiceStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/internal/v1/replies", this::handle);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  synchronized void answer(int status, String body) {
    answers.add(new Answer(status, body, () -> {}));
  }

  /** Runs beforeAnswer (e.g. a handoff made by the agent) while the call is in progress. */
  synchronized void answerAfter(Runnable beforeAnswer, String body) {
    answers.add(new Answer(200, body, beforeAnswer));
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  synchronized void reset() {
    answers.clear();
    received.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(new Received(exchange.getRequestHeaders().getFirst("X-Api-Key"), body));
    Answer answer;
    synchronized (this) {
      answer = answers.isEmpty() ? new Answer(200, OK_REPLY, () -> {}) : answers.poll();
    }
    answer.beforeAnswer().run();
    byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(answer.status(), bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
