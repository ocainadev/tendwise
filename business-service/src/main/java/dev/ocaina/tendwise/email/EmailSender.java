package dev.ocaina.tendwise.email;

/**
 * Sends the transactional emails (port). Until T69 adds a real provider, {@link LogEmailSender}
 * only writes a log line.
 *
 * <p>Each kind of email has its own method, so an adapter knows which parts are secret (the token
 * in a link) and never logs them.
 */
public interface EmailSender {

  void sendInvitation(InvitationEmail email);
}
