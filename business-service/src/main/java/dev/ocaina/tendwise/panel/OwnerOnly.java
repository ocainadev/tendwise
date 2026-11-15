package dev.ocaina.tendwise.panel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a panel route (or a whole panel controller) that only the business's OWNER may call. An
 * AGENT gets 403. Routes without it are open to every member (AGENT or OWNER).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface OwnerOnly {}
