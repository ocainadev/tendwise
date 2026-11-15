package dev.ocaina.tendwise.panel;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts PanelGuard in front of every panel route and lets controllers take a CurrentMembership. */
@Configuration
class PanelWebConfig implements WebMvcConfigurer {

  private final PanelGuard panelGuard;

  PanelWebConfig(PanelGuard panelGuard) {
    this.panelGuard = panelGuard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(panelGuard).addPathPatterns(PanelGuard.PREFIX + "**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentMembershipResolver());
  }
}
