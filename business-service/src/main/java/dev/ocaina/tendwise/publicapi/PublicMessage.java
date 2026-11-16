package dev.ocaina.tendwise.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageSender;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A message as the customer sees it. No metadata and no user ids: those are for staff only. Only
 * two harmless parts of the metadata are copied (T42): the SYSTEM event, so the widget can show its
 * own translation ("Maria entrou na conversa"), and the titles of the AI reply's sources.
 *
 * @param authorName first name of the staff member: the author of a HUMAN message, or who joined
 *     for a SYSTEM TAKEN_OVER message; otherwise null
 * @param event SYSTEM messages only: the SystemEvent name (TAKEN_OVER, RELEASED, AUTO_ACK, …)
 * @param sources AI messages only: the knowledge-base sections the reply was based on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicMessage(
    Long id,
    MessageSender sender,
    String authorName,
    String body,
    Instant createdAt,
    String event,
    List<Source> sources) {

  /** A knowledge-base section: document title and section heading (null when none). */
  public record Source(String title, String section) {}

  static PublicMessage from(Message message, String authorName) {
    Map<String, Object> metadata = message.getMetadata() == null ? Map.of() : message.getMetadata();
    String event =
        message.getSender() == MessageSender.SYSTEM && metadata.get("event") instanceof String name
            ? name
            : null;
    List<Source> sources = message.getSender() == MessageSender.AI ? sources(metadata) : null;
    return new PublicMessage(
        message.getId(),
        message.getSender(),
        authorName,
        message.getBody(),
        message.getCreatedAt(),
        event,
        sources);
  }

  /**
   * metadata.sources is a list of maps (title, source_path, section); source_path stays private.
   */
  private static List<Source> sources(Map<String, Object> metadata) {
    if (!(metadata.get("sources") instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .filter(source -> source.get("title") instanceof String)
        .map(source ->
            new Source(
                (String) source.get("title"),
                source.get("section") instanceof String section ? section : null))
        .toList();
  }
}
