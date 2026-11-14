package dev.ocaina.tendwise.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /auth/login. The size limits only stop absurdly large inputs. */
public record LoginRequest(
    @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 1024) String password) {

  /** Never print the password, for example in a log line or an error. */
  @Override
  public String toString() {
    return "LoginRequest[email=***, password=***]";
  }
}
