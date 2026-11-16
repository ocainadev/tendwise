package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.inbox.ConversationListResponse.Item;
import dev.ocaina.tendwise.inbox.ConversationListResponse.LastMessage;
import dev.ocaina.tendwise.inbox.ConversationListResponse.TabCount;
import dev.ocaina.tendwise.inbox.ConversationListResponse.TabCounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The inbox list in plain SQL: one query gives a whole page with its assignee names, last message
 * previews, handoff reasons and waiting times (no query per row), and one more gives the tab
 * counts. Every query filters by business_id.
 */
@Repository
class InboxQueries {

  /** Characters of the last message shown in the list. */
  static final int PREVIEW_LENGTH = 120;

  /**
   * The page. The WHERE conditions marked {@code %s} are added by {@link #list} only when the
   * filter is used. The LATERAL joins run once per row of the page, each with an index:
   *
   * <ul>
   *   <li>last_message: newest message that is not SYSTEM (idx_messages_conversation_id).
   *   <li>open_handoff: newest handoff not resolved yet (idx_handoffs_business_id_status).
   *   <li>waiting: when it moved to WAITING, from the SYSTEM message of that move (HANDOFF_CREATED
   *       or AI_FAILED). Only looked up for WAITING conversations.
   * </ul>
   */
  private static final String LIST_SQL =
      """
      SELECT c.id, c.state, c.channel, c.language, c.assignee_user_id, u.name AS assignee_name,
             c.last_message_at, c.unread_by_staff,
             last_message.sender AS last_sender, last_message.preview AS last_preview,
             last_message.internal AS last_internal,
             open_handoff.reason AS handoff_reason,
             waiting.created_at AS waiting_since
      FROM core.conversations c
      LEFT JOIN core.users u ON u.id = c.assignee_user_id
      LEFT JOIN LATERAL (
          SELECT m.sender, LEFT(m.body, :previewLength) AS preview, m.internal
          FROM core.messages m
          WHERE m.conversation_id = c.id AND m.business_id = c.business_id
            AND m.sender <> 'SYSTEM'
          ORDER BY m.id DESC
          LIMIT 1
      ) last_message ON TRUE
      LEFT JOIN LATERAL (
          SELECT h.reason
          FROM core.handoffs h
          WHERE h.business_id = c.business_id AND h.conversation_id = c.id
            AND h.status IN ('OPEN', 'IN_PROGRESS')
          ORDER BY h.id DESC
          LIMIT 1
      ) open_handoff ON TRUE
      LEFT JOIN LATERAL (
          SELECT m.created_at
          FROM core.messages m
          WHERE c.state = 'WAITING'
            AND m.conversation_id = c.id AND m.business_id = c.business_id
            AND m.sender = 'SYSTEM' AND m.metadata ->> 'event' IN ('HANDOFF_CREATED', 'AI_FAILED')
          ORDER BY m.id DESC
          LIMIT 1
      ) waiting ON TRUE
      WHERE c.business_id = :businessId
      %s
      ORDER BY c.last_message_at DESC, c.id DESC
      LIMIT :limit
      """;

  private static final String COUNTS_SQL =
      """
      SELECT
          COUNT(*) FILTER (WHERE state = 'WAITING') AS waiting,
          COUNT(*) FILTER (WHERE state = 'WAITING' AND unread_by_staff > 0) AS waiting_unread,
          COUNT(*) FILTER (WHERE state = 'HUMAN' AND assignee_user_id = :userId) AS mine,
          COUNT(*) FILTER (WHERE state = 'HUMAN' AND assignee_user_id = :userId
                           AND unread_by_staff > 0) AS mine_unread,
          COUNT(*) FILTER (WHERE state = 'AI') AS ai,
          COUNT(*) FILTER (WHERE state = 'AI' AND unread_by_staff > 0) AS ai_unread
      FROM core.conversations
      WHERE business_id = :businessId AND state IN ('WAITING', 'HUMAN', 'AI')
      """;

  private final JdbcClient jdbc;

  InboxQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One page of conversations, newest message first. Asks for {@code limit} rows: the caller asks
   * for one more than it shows, to know whether there is a next page.
   */
  List<Item> list(long businessId, InboxFilter filter, int limit) {
    List<String> conditions = new ArrayList<>();
    if (filter.state() != null) {
      conditions.add("AND c.state = :state");
    }
    if (filter.assigneeUserId() != null) {
      conditions.add("AND c.assignee_user_id = :assigneeUserId");
    }
    if (filter.query() != null) {
      // Staff search every message they can see in the panel, internal notes included.
      conditions.add(
          """
          AND EXISTS (
              SELECT 1 FROM core.messages s
              WHERE s.conversation_id = c.id AND s.business_id = c.business_id
                AND s.sender <> 'SYSTEM' AND s.body ILIKE :pattern)\
          """);
    }
    if (filter.cursor() != null) {
      // Rows that come after the cursor in the ORDER BY below.
      conditions.add("AND (c.last_message_at, c.id) < (:cursorAt, :cursorId)");
    }

    JdbcClient.StatementSpec statement =
        jdbc.sql(LIST_SQL.formatted(String.join("\n", conditions)))
            .param("businessId", businessId)
            .param("previewLength", PREVIEW_LENGTH)
            .param("limit", limit);
    if (filter.state() != null) {
      statement = statement.param("state", filter.state().name());
    }
    if (filter.assigneeUserId() != null) {
      statement = statement.param("assigneeUserId", filter.assigneeUserId());
    }
    if (filter.query() != null) {
      statement = statement.param("pattern", "%" + escapeLike(filter.query()) + "%");
    }
    if (filter.cursor() != null) {
      statement =
          statement
              .param(
                  "cursorAt",
                  OffsetDateTime.ofInstant(filter.cursor().lastMessageAt(), ZoneOffset.UTC))
              .param("cursorId", filter.cursor().id());
    }
    return statement.query((rs, rowNumber) -> toItem(rs)).list();
  }

  /** The tab badges for this user. */
  TabCounts counts(long businessId, long userId) {
    return jdbc.sql(COUNTS_SQL)
        .param("businessId", businessId)
        .param("userId", userId)
        .query((rs, rowNumber) ->
            new TabCounts(
                new TabCount(rs.getLong("waiting"), rs.getLong("waiting_unread")),
                new TabCount(rs.getLong("mine"), rs.getLong("mine_unread")),
                new TabCount(rs.getLong("ai"), rs.getLong("ai_unread"))))
        .single();
  }

  private static Item toItem(ResultSet rs) throws SQLException {
    Long assigneeId = rs.getObject("assignee_user_id", Long.class);
    Assignee assignee =
        assigneeId == null ? null : new Assignee(assigneeId, rs.getString("assignee_name"));

    String lastSender = rs.getString("last_sender");
    LastMessage lastMessage =
        lastSender == null
            ? null
            : new LastMessage(
                MessageSender.valueOf(lastSender),
                rs.getString("last_preview"),
                rs.getBoolean("last_internal"));

    String handoffReason = rs.getString("handoff_reason");
    return new Item(
        rs.getObject("id", UUID.class),
        ConversationState.valueOf(rs.getString("state")),
        Channel.valueOf(rs.getString("channel")),
        rs.getString("language"),
        assignee,
        lastMessage,
        toInstant(rs, "last_message_at"),
        rs.getInt("unread_by_staff"),
        handoffReason == null ? null : HandoffReason.valueOf(handoffReason),
        toInstant(rs, "waiting_since"));
  }

  private static Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** "50%_off" must match those characters, not "any text": escape LIKE's wildcards. */
  static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
