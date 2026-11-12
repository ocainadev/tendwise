package dev.ocaina.tendwise;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.catalog.CatalogItem;
import dev.ocaina.tendwise.catalog.CatalogItemRepository;
import dev.ocaina.tendwise.handoff.HandoffRepository;
import dev.ocaina.tendwise.order.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Runs the Flyway migrations against a real PostgreSQL and checks the seed through the
 * repositories. Hibernate's ddl-auto=validate also fails this test if an entity does not match the
 * schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SeedDataRepositoryTest {

  @Autowired private BusinessRepository businesses;
  @Autowired private CatalogItemRepository catalogItems;
  @Autowired private OrderRepository orders;
  @Autowired private HandoffRepository handoffs;

  @Test
  void seedHasTwoBusinesses() {
    assertThat(businesses.count()).isEqualTo(2);

    Business pizza = businesses.findBySlug("joes-pizza").orElseThrow();
    assertThat(pizza.getName()).isEqualTo("Joe's Pizza");
    assertThat(pizza.getDefaultLanguage()).isEqualTo("en");
    assertThat(pizza.getOpeningHours()).contains("\"fri\"");
    assertThat(pizza.getCreatedAt()).isNotNull();

    Business denim = businesses.findBySlug("denim-corner").orElseThrow();
    assertThat(denim.getDefaultLanguage()).isEqualTo("pt");
  }

  @Test
  void unknownSlugReturnsEmpty() {
    assertThat(businesses.findBySlug("does-not-exist")).isEmpty();
  }

  @Test
  void catalogIsSeparatedByBusiness() {
    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    Long denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();

    List<CatalogItem> pizzaItems = catalogItems.findByBusinessIdOrderByNameAsc(pizzaId);
    List<CatalogItem> denimItems = catalogItems.findByBusinessIdOrderByNameAsc(denimId);

    assertThat(catalogItems.count()).isEqualTo(30);
    assertThat(pizzaItems).hasSize(15).allMatch(item -> item.getBusinessId().equals(pizzaId));
    assertThat(pizzaItems).extracting(CatalogItem::getCurrency).containsOnly("USD");
    assertThat(denimItems).hasSize(15).allMatch(item -> item.getBusinessId().equals(denimId));
    assertThat(denimItems).extracting(CatalogItem::getName).doesNotContain("Margherita");
  }

  @Test
  void seedHasTenOrdersPerBusiness() {
    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    Long denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();

    assertThat(orders.count()).isEqualTo(20);
    assertThat(orders.countByBusinessId(pizzaId)).isEqualTo(10);
    assertThat(orders.countByBusinessId(denimId)).isEqualTo(10);
  }

  @Test
  void handoffsStartEmpty() {
    assertThat(handoffs.count()).isZero();
  }
}
