package dev.ocaina.tendwise.panel;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills a controller parameter of type {@link CurrentMembership} with what PanelGuard stored. */
class CurrentMembershipResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(CurrentMembership.class);
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object membership =
        webRequest.getAttribute(PanelGuard.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (membership == null) {
      // A programming error: the controller is not under /api/v1/panel/{slug}/.
      throw new IllegalStateException(
          "CurrentMembership is only available under " + PanelGuard.PREFIX + "{slug}/");
    }
    return membership;
  }
}
