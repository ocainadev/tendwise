package dev.ocaina.tendwise.business;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

  private final BusinessService businessService;

  public BusinessController(BusinessService businessService) {
    this.businessService = businessService;
  }

  /** Name, category, language, time zone and opening hours. 404 if the slug is unknown. */
  @GetMapping("/{slug}")
  public BusinessResponse getBusiness(@PathVariable String slug) {
    return businessService.getBusiness(slug);
  }

  /** Catalog ordered by name, unavailable items included. 404 if the slug is unknown. */
  @GetMapping("/{slug}/catalog")
  public List<CatalogItemResponse> getCatalog(@PathVariable String slug) {
    return businessService.getCatalog(slug);
  }
}
