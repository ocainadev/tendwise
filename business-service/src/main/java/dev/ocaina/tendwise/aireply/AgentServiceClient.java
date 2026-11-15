package dev.ocaina.tendwise.aireply;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls agent-service's internal API (docs/architecture.md, "Internal API"). The base URL comes
 * from AGENT_SERVICE_URL; the X-Api-Key header carries the shared service key
 * (BUSINESS_SERVICE_API_KEY), the same one agent-service sends to us.
 *
 * <p>Any HTTP error (4xx, 5xx) or timeout is thrown as a RestClientException; the job worker counts
 * it as a failed attempt.
 */
@Component
public class AgentServiceClient {

  private final RestClient restClient;

  public AgentServiceClient(
      RestClient.Builder builder,
      @Value("${tendwise.agent-service.url}") String baseUrl,
      @Value("${tendwise.api-key}") String apiKey,
      @Value("${tendwise.agent-service.timeout}") Duration timeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    // An LLM turn takes a few seconds; past this the attempt fails and is retried.
    requestFactory.setReadTimeout(timeout);
    this.restClient =
        builder
            .baseUrl(baseUrl)
            .defaultHeader("X-Api-Key", apiKey)
            .requestFactory(requestFactory)
            .build();
  }

  /** POST /internal/v1/replies: one agent turn for a customer message. */
  public ReplyResponse reply(ReplyRequest request) {
    ReplyResponse response =
        restClient
            .post()
            .uri("/internal/v1/replies")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(ReplyResponse.class);
    if (response == null || response.reply() == null || response.reply().isBlank()) {
      throw new IllegalStateException("agent-service returned no reply");
    }
    return response;
  }
}
