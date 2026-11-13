package dev.ocaina.tendwise.business;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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

/** Full stack test against the seeded PostgreSQL (joes-pizza and denim-corner, 15 items each). */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BusinessApiTest {

  @Autowired private MockMvc mockMvc;

  private ResultActions request(String path, String slug) throws Exception {
    return mockMvc.perform(get(path, slug).header("X-Api-Key", "test-key"));
  }

  @Test
  void returnsBusinessInfoWithOpeningHoursAsJson() throws Exception {
    request("/api/v1/businesses/{slug}", "joes-pizza")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("joes-pizza"))
        .andExpect(jsonPath("$.name").value("Joe's Pizza"))
        .andExpect(jsonPath("$.category").value("restaurant"))
        .andExpect(jsonPath("$.defaultLanguage").value("en"))
        .andExpect(jsonPath("$.timezone").value("America/New_York"))
        .andExpect(jsonPath("$.openingHours.mon", hasSize(0)))
        .andExpect(jsonPath("$.openingHours.fri[0].close").value("23:30"))
        .andExpect(jsonPath("$.id").doesNotExist());
  }

  @Test
  void unknownBusinessReturns404() throws Exception {
    expectBusinessNotFound(request("/api/v1/businesses/{slug}", "no-such-business"));
  }

  @Test
  void unknownBusinessCatalogReturns404() throws Exception {
    expectBusinessNotFound(request("/api/v1/businesses/{slug}/catalog", "no-such-business"));
  }

  @Test
  void joesPizzaCatalogIsSortedByNameInUsd() throws Exception {
    request("/api/v1/businesses/{slug}/catalog", "joes-pizza")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(15)))
        // Alphabetical: "BBQ Chicken" comes first, "Veggie Supreme" last.
        .andExpect(jsonPath("$[0].name").value("BBQ Chicken"))
        .andExpect(jsonPath("$[14].name").value("Veggie Supreme"))
        .andExpect(jsonPath("$[*].currency", everyItem(is("USD"))))
        .andExpect(jsonPath("$[?(@.name == 'Margherita')].price").value(14.0))
        .andExpect(jsonPath("$[?(@.name == 'Hawaiian')].available").value(false));
  }

  @Test
  void denimCornerCatalogHasOnlyItsOwnItemsInBrl() throws Exception {
    request("/api/v1/businesses/{slug}/catalog", "denim-corner")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(15)))
        .andExpect(jsonPath("$[0].name").value("Basic Black T-Shirt"))
        .andExpect(jsonPath("$[14].name").value("Wide Leg Jeans"))
        .andExpect(jsonPath("$[*].currency", everyItem(is("BRL"))))
        .andExpect(jsonPath("$[*].name", not(hasItem("Margherita"))))
        .andExpect(jsonPath("$[14].available").value(false))
        .andExpect(jsonPath("$[14].price").value(219.9));
  }

  @Test
  void missingApiKeyReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/businesses/{slug}", "joes-pizza"))
        .andExpect(status().isUnauthorized());
  }

  private void expectBusinessNotFound(ResultActions result) throws Exception {
    result
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Business not found"));
  }
}
