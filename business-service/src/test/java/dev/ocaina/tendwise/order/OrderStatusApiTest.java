package dev.ocaina.tendwise.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Full stack test: API-key filter, controller, service and the seeded PostgreSQL. Seed order
 * JP-1042 belongs to joes-pizza, phone +1 212-555-0102, status IN_PROGRESS.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderStatusApiTest {

  private static final String VALID_KEY = "test-key";

  @Autowired private MockMvc mockMvc;

  private ResultActions requestStatus(String code, String slug, String last4, String apiKey)
      throws Exception {
    var request =
        get("/api/v1/orders/{code}/status", code)
            .param("businessSlug", slug)
            .param("phoneLast4", last4);
    if (apiKey != null) {
      request.header("X-Api-Key", apiKey);
    }
    return mockMvc.perform(request);
  }

  @Test
  void returnsStatusWhenCodeAndPhoneMatch() throws Exception {
    requestStatus("JP-1042", "joes-pizza", "0102", VALID_KEY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("JP-1042"))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.fulfilment").value("DELIVERY"))
        .andExpect(jsonPath("$.eta").exists())
        // Personal data never leaves the service.
        .andExpect(jsonPath("$.customerName").doesNotExist())
        .andExpect(jsonPath("$.customerPhone").doesNotExist());
  }

  @Test
  void unknownCodeReturns404() throws Exception {
    expectOrderNotFound(requestStatus("JP-9999", "joes-pizza", "0102", VALID_KEY));
  }

  @Test
  void wrongPhoneReturnsTheSame404AsUnknownCode() throws Exception {
    expectOrderNotFound(requestStatus("JP-1042", "joes-pizza", "9999", VALID_KEY));
  }

  @Test
  void orderOfAnotherBusinessReturnsTheSame404() throws Exception {
    // DC-2001 exists with phone ...0201, but belongs to denim-corner.
    expectOrderNotFound(requestStatus("DC-2001", "joes-pizza", "0201", VALID_KEY));
  }

  @Test
  void unknownBusinessReturnsTheSame404() throws Exception {
    expectOrderNotFound(requestStatus("JP-1042", "no-such-business", "0102", VALID_KEY));
  }

  @Test
  void phoneLast4MustBeFourDigits() throws Exception {
    requestStatus("JP-1042", "joes-pizza", "12a4", VALID_KEY)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void missingApiKeyReturns401() throws Exception {
    requestStatus("JP-1042", "joes-pizza", "0102", null)
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void wrongApiKeyReturns401() throws Exception {
    requestStatus("JP-1042", "joes-pizza", "0102", "wrong-key")
        .andExpect(status().isUnauthorized());
  }

  @Test
  void swaggerUiIsPublic() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  /** Same status, title and detail for every "not found" case. */
  private void expectOrderNotFound(ResultActions result) throws Exception {
    result
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Order not found"))
        .andExpect(jsonPath("$.detail").value("No order matches this code and phone number."));
  }
}
