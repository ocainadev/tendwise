package dev.ocaina.tendwise.email;

/** Emails in logs are masked: "joana@example.com" becomes "j***@example.com". */
public final class EmailMasking {

  private EmailMasking() {}

  /** Keeps the first letter and the domain. Something that is not an email becomes "***". */
  public static String mask(String email) {
    if (email == null) {
      return "***";
    }
    int at = email.indexOf('@');
    if (at < 0) {
      return "***";
    }
    String first = at > 0 ? email.substring(0, 1) : "";
    return first + "***" + email.substring(at);
  }
}
