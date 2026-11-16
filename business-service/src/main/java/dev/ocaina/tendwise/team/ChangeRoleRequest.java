package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/v1/panel/{slug}/team/{userId}. */
public record ChangeRoleRequest(@NotNull MembershipRole role) {}
