package dev.ocaina.tendwise.inbox;

/**
 * The staff member a conversation is assigned to. name is null when the user no longer exists
 * (assignee_user_id has no foreign key yet).
 */
public record Assignee(long id, String name) {}
