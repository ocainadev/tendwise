package dev.ocaina.tendwise.settings;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ocaina.tendwise.panel.CurrentMembership;
import dev.ocaina.tendwise.panel.OwnerOnly;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The settings pages (T53). OWNER only; PanelGuard checks the business and the role first. */
@RestController
@OwnerOnly
@SecurityRequirements // Session cookies, not X-Api-Key.
public class SettingsController {

  private final SettingsService settingsService;

  public SettingsController(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping("/api/v1/panel/{slug}/settings")
  BusinessSettings get(CurrentMembership membership) {
    return settingsService.get(membership.businessId());
  }

  /**
   * Replaces all settings and returns them. The body is read as a JSON tree so that the service can
   * reject unknown fields (Spring ignores them by default) with a 400 that names them.
   */
  @PutMapping("/api/v1/panel/{slug}/settings")
  BusinessSettings update(CurrentMembership membership, @RequestBody JsonNode body) {
    return settingsService.update(membership.businessId(), body);
  }
}
