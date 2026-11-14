package dev.ocaina.tendwise.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Adding Spring Security must not change the service routes or Swagger. */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRoutesTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void serviceRoutesStillUseOnlyTheApiKey() throws Exception {
    mockMvc
        .perform(get("/api/v1/businesses/joes-pizza"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Missing or invalid X-Api-Key header."));
    mockMvc
        .perform(get("/api/v1/businesses/joes-pizza").header("X-Api-Key", "test-key"))
        .andExpect(status().isOk());
  }

  @Test
  void servicePostsNeedNoCsrfHeader() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/handoffs")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        // Reaches the controller: a validation error, not a 403.
        .andExpect(status().isBadRequest());
  }

  @Test
  void swaggerIsPublic() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  void theJwksIsPublic() throws Exception {
    mockMvc.perform(get("/auth/jwks.json")).andExpect(status().isOk());
  }

  @Test
  void routesThatWereNotOpenedOnPurposeAreClosed() throws Exception {
    mockMvc.perform(get("/something-else")).andExpect(status().isUnauthorized());
  }
}
