package dev.ocaina.tendwise.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The one thread per instance that LISTENs on the {@link EventPublisher#CHANNEL} channel and hands
 * every event to {@link EventStreams} (docs/architecture.md, "Real-time").
 *
 * <p>It has its own JDBC connection, outside the Hikari pool: the connection stays open for as long
 * as the service runs, and a pool would recycle it. When the connection breaks (database restart,
 * network), the thread waits {@code tendwise.realtime.reconnect-delay} and connects again. Events
 * sent while it was disconnected are lost, so after a reconnect every open stream gets a {@code
 * resync} hint and its client refetches.
 */
@Component
class NotificationListener implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

  /** Shown in pg_stat_activity, so the connection is easy to find (and the tests can kill it). */
  static final String APPLICATION_NAME = "tendwise-event-listener";

  /** How long one wait for notifications lasts; also how quickly stop() is noticed. */
  private static final int WAIT_MILLIS = 1_000;

  private final JdbcConnectionDetails database;
  private final EventStreams streams;
  private final ObjectMapper objectMapper;
  private final Duration reconnectDelay;

  private volatile boolean running;
  private Thread thread;

  NotificationListener(
      JdbcConnectionDetails database,
      EventStreams streams,
      ObjectMapper objectMapper,
      @Value("${tendwise.realtime.reconnect-delay}") Duration reconnectDelay) {
    this.database = database;
    this.streams = streams;
    this.objectMapper = objectMapper;
    this.reconnectDelay = reconnectDelay;
  }

  @Override
  public void start() {
    running = true;
    thread = Thread.ofPlatform().name("event-listener").daemon().start(this::listenForever);
  }

  /**
   * Shutdown: stops listening and ends the open streams. It runs before the web server's graceful
   * shutdown (a higher phase stops first), which would otherwise wait for the streams.
   */
  @Override
  public void stop() {
    running = false;
    try {
      // The thread notices within one WAIT_MILLIS and closes its connection.
      thread.join(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    streams.closeAll();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void listenForever() {
    boolean firstConnection = true;
    while (running) {
      try (Connection connection = connect()) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + EventPublisher.CHANNEL);
        }
        if (!firstConnection) {
          log.info("Event listener reconnected; asking open streams to resync");
          streams.resyncAll();
        }
        firstConnection = false;
        receiveUntilStopped(connection.unwrap(PGConnection.class));
      } catch (SQLException | RuntimeException e) {
        if (running) {
          log.warn(
              "Event listener lost its connection ({}); reconnecting in {}",
              e.getMessage(),
              reconnectDelay);
          sleep(reconnectDelay);
        }
      }
    }
  }

  private void receiveUntilStopped(PGConnection connection) throws SQLException {
    while (running) {
      // Blocks until a notification arrives or WAIT_MILLIS passes (then returns an empty array).
      PGNotification[] notifications = connection.getNotifications(WAIT_MILLIS);
      if (notifications == null) {
        continue;
      }
      for (PGNotification notification : notifications) {
        deliver(notification.getParameter());
      }
    }
  }

  private void deliver(String payload) {
    RealtimeEvent event;
    try {
      event = objectMapper.readValue(payload, RealtimeEvent.class);
    } catch (IOException e) {
      log.warn("Ignoring an event that could not be read: {}", e.getMessage());
      return;
    }
    try {
      streams.deliver(event);
    } catch (RuntimeException e) {
      // One bad stream or event must never stop the listener thread.
      log.warn("Could not deliver a {} event: {}", event.type(), e.getMessage());
    }
  }

  private Connection connect() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", database.getUsername());
    if (database.getPassword() != null) {
      properties.setProperty("password", database.getPassword());
    }
    properties.setProperty("ApplicationName", APPLICATION_NAME);
    // Lets the operating system notice a silently dropped connection.
    properties.setProperty("tcpKeepAlive", "true");
    properties.setProperty("logServerErrorDetail", "false");
    return DriverManager.getConnection(database.getJdbcUrl(), properties);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
