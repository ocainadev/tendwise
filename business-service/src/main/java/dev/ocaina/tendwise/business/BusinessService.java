package dev.ocaina.tendwise.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.catalog.CatalogItemRepository;
import dev.ocaina.tendwise.shared.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {

  private final BusinessRepository businesses;
  private final CatalogItemRepository catalogItems;
  private final ObjectMapper objectMapper;

  public BusinessService(
      BusinessRepository businesses,
      CatalogItemRepository catalogItems,
      ObjectMapper objectMapper) {
    this.businesses = businesses;
    this.catalogItems = catalogItems;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public BusinessResponse getBusiness(String slug) {
    Business business = findBusiness(slug);
    return new BusinessResponse(
        business.getSlug(),
        business.getName(),
        business.getCategory(),
        business.getDefaultLanguage(),
        business.getTimezone(),
        parseOpeningHours(business.getOpeningHours()));
  }

  /** All items of the business ordered by name, including unavailable ones. */
  @Transactional(readOnly = true)
  public List<CatalogItemResponse> getCatalog(String slug) {
    Business business = findBusiness(slug);
    return catalogItems.findByBusinessIdOrderByNameAsc(business.getId()).stream()
        .map(CatalogItemResponse::from)
        .toList();
  }

  private Business findBusiness(String slug) {
    return businesses
        .findBySlug(slug)
        .orElseThrow(
            () -> new NotFoundException("Business not found", "No business has this slug."));
  }

  /**
   * The entity keeps opening_hours as a raw JSON string. Parsing it into a JsonNode is the simplest
   * way to return it as a JSON object without writing a Java class for every day.
   */
  private JsonNode parseOpeningHours(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException exception) {
      // The column is JSONB, so PostgreSQL already rejected invalid JSON: this should never happen.
      throw new IllegalStateException("Stored opening_hours is not valid JSON", exception);
    }
  }
}
