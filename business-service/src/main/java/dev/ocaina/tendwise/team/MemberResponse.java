package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;
import java.time.Instant;

/** One member of the business's team. */
public record MemberResponse(
    long userId, String name, String email, MembershipRole role, Instant joinedAt) {}
