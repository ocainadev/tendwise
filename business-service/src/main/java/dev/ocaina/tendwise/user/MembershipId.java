package dev.ocaina.tendwise.user;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** Primary key of a membership: one row per (user, business) pair. */
@Embeddable
public record MembershipId(Long userId, Long businessId) implements Serializable {}
