package dev.ocaina.tendwise.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.job.JobRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A rejected handoff (conversation not in AI, 409) leaves nothing behind: the handoff row is
 * written before the state change, and the transaction must roll it back. Not
 * {@code @Transactional}, so each request commits or rolls back for real. Only rejected handoffs
 * are created here, so no handoff row survives this class (SeedDataRepositoryTest expects none).
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HandoffRollbackTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversationService;
  @Autowired private HandoffRepository handoffs;
  @Autowired private JobRepository jobs;

  private Long pizzaId;

  @BeforeEach
  void findSeededBusiness() {
    pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
  }

  @Test
  void conversationNotInAiStoresNoHandoffAndNoJob() throws Exception {
    UUID id = startPizzaConversation();
    // WAITING without a handoff row, so no handoff of this class is ever committed.
    conversationService.handOff(id, pizzaId, HandoffReason.HUMAN_REQUEST, 1L);

    createHandoff("joes-pizza", id).andExpect(status().isConflict());

    assertNothingStoredFor(id);
  }

  private void assertNothingStoredFor(UUID conversationId) {
    assertThat(handoffs.findAll())
        .noneMatch(handoff -> handoff.getConversationId().equals(conversationId));
    assertThat(jobs.findAll())
        .noneMatch(job -> conversationId.toString().equals(job.getPayload().get("conversationId")));
  }

  private UUID startPizzaConversation() {
    return conversationService
        .start(pizzaId, Channel.WEB, "en", UUID.randomUUID().toString(), null)
        .getId();
  }

  private ResultActions createHandoff(String slug, UUID conversationId) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of(
                "businessSlug",
                slug,
                "conversationId",
                conversationId,
                "reason",
                "HUMAN_REQUEST",
                "summary",
                "Wants to talk to a person."));
    return mockMvc.perform(
        post("/api/v1/handoffs")
            .header("X-Api-Key", "test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
