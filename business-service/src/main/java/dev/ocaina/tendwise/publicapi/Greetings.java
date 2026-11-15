package dev.ocaina.tendwise.publicapi;

import java.util.Map;

/**
 * The first message the widget shows. Fixed texts for now; T53 lets the owner write their own
 * (assistant_settings.greeting).
 */
final class Greetings {

  private static final Map<String, String> TEMPLATES =
      Map.of(
          "en", "Hi! Welcome to %s. I'm the virtual assistant. How can I help you today?",
          "es", "¡Hola! Bienvenido a %s. Soy el asistente virtual. ¿En qué puedo ayudarte?",
          "pt", "Olá! Sou o assistente virtual de %s. Como posso ajudar você hoje?");

  private Greetings() {}

  /** language is en, es or pt; anything else falls back to English. */
  static String forBusiness(String language, String businessName) {
    return TEMPLATES.getOrDefault(language, TEMPLATES.get("en")).formatted(businessName);
  }
}
