package dev.ocaina.tendwise.panel;

import dev.ocaina.tendwise.user.MembershipRole;

/**
 * The business a panel request is for, and the user's role in it. {@link PanelGuard} builds it from
 * the URL slug and the access token before the controller runs; a controller under {@code
 * /api/v1/panel/{slug}/**} gets it by declaring a parameter of this type.
 *
 * <p>Always use {@link #businessId()} in queries, never an id from the request body (tenant
 * isolation).
 */
public record CurrentMembership(long businessId, String slug, MembershipRole role) {}
