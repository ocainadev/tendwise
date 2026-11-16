package dev.ocaina.tendwise.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The email adapter until T69: sends nothing, only logs that an email would have gone out, with the
 * address masked.
 *
 * <p>The accept link holds the invitation token, so it is logged only without the token, unless
 * {@code tendwise.email.log-links} is true (EMAIL_LOG_LINKS=true). Turn that on only on your own
 * machine, to open the link while testing locally; it is off by default.
 */
@Component
public class LogEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

  private final boolean logLinks;

  public LogEmailSender(@Value("${tendwise.email.log-links}") boolean logLinks) {
    this.logLinks = logLinks;
  }

  @Override
  public void sendInvitation(InvitationEmail email) {
    String url = logLinks ? email.acceptUrl() : email.urlWithoutToken();
    log.info("invitation email sent to {} (link: {})", EmailMasking.mask(email.to()), url);
  }
}
