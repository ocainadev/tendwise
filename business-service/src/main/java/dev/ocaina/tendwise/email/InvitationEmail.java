package dev.ocaina.tendwise.email;

import dev.ocaina.tendwise.user.MembershipRole;

/**
 * An invitation to join a business's team.
 *
 * @param acceptUrl the web page that accepts it, with the token after {@code #token=}. The token is
 *     a secret: never log the full URL (see {@link #urlWithoutToken()}).
 */
public record InvitationEmail(
    String to, String businessName, MembershipRole role, String acceptUrl) {

  /** The accept URL with the {@code #token=...} part cut off, safe to log. */
  public String urlWithoutToken() {
    int hash = acceptUrl.indexOf('#');
    return hash < 0 ? acceptUrl : acceptUrl.substring(0, hash);
  }

  /** Never print the token or the full email, for example in an error. */
  @Override
  public String toString() {
    return "InvitationEmail[to=" + EmailMasking.mask(to) + ", url=" + urlWithoutToken() + "]";
  }
}
