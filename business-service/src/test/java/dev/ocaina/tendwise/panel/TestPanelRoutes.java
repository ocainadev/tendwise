package dev.ocaina.tendwise.panel;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controllers, so the guard and the admin rule can be tested before the real panel and
 * admin routes exist (T43+, T62). Only the test classes that import this configuration have them.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPanelRoutes {

  static final String MEMBER_ROUTE = "/api/v1/panel/{slug}/test-guard";
  static final String OWNER_ROUTE = "/api/v1/panel/{slug}/test-guard/owner-only";
  static final String ADMIN_ROUTE = "/api/v1/admin/test-ping";

  @RestController
  static class MemberController {

    /** Any member: echoes what the guard found. */
    @GetMapping(MEMBER_ROUTE)
    CurrentMembership read(CurrentMembership membership) {
      return membership;
    }

    @PostMapping(MEMBER_ROUTE)
    CurrentMembership write(CurrentMembership membership) {
      return membership;
    }
  }

  @RestController
  @OwnerOnly
  static class OwnerController {

    @PostMapping(OWNER_ROUTE)
    CurrentMembership write(CurrentMembership membership) {
      return membership;
    }
  }

  @RestController
  static class AdminController {

    @GetMapping(ADMIN_ROUTE)
    ResponseEntity<String> ping() {
      return ResponseEntity.ok("pong");
    }
  }
}
