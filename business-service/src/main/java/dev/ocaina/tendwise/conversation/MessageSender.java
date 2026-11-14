package dev.ocaina.tendwise.conversation;

public enum MessageSender {
  CUSTOMER,
  AI,
  HUMAN,
  /** Written by business-service to record a state change (metadata.event). */
  SYSTEM
}
