package dev.ocaina.tendwise.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query takes a businessId, so one business never sees another's messages. */
public interface MessageRepository extends JpaRepository<Message, Long> {

  List<Message> findByConversationIdAndBusinessIdOrderByIdAsc(UUID conversationId, Long businessId);
}
