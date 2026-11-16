package dev.ocaina.tendwise.business;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public info about a business. The internal id stays hidden: callers use the slug.
 *
 * <p>openingHours is a JsonNode, so it is serialised as a real JSON object (e.g. {"fri": [{"open":
 * "11:00", "close": "23:30"}]}) and not as an escaped string.
 *
 * <p>assistantSettings (T53) is read by the agent from T54: name, tone, greeting, extra
 * instructions, enabled languages and the out-of-hours message.
 */
public record BusinessResponse(
    String slug,
    String name,
    String category,
    String defaultLanguage,
    String timezone,
    JsonNode openingHours,
    AssistantSettings assistantSettings) {}
