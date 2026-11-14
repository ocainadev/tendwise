package dev.ocaina.tendwise.auth;

/**
 * Any failed login or refresh. Always answered with 401 and one fixed message, so the response
 * never tells an unknown email, a wrong password, a locked or a disabled account apart.
 */
public class AuthFailedException extends RuntimeException {

  private AuthFailedException(String message) {
    super(message);
  }

  static AuthFailedException badCredentials() {
    return new AuthFailedException("Invalid email or password.");
  }

  static AuthFailedException sessionEnded() {
    return new AuthFailedException("Your session has ended. Please log in again.");
  }
}
