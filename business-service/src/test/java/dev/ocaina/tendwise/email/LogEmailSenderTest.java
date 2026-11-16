package dev.ocaina.tendwise.email;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ocaina.tendwise.user.MembershipRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogEmailSenderTest {

  private static final InvitationEmail EMAIL =
      new InvitationEmail(
          "joana@example.com",
          "Joe's Pizza",
          MembershipRole.AGENT,
          "http://localhost:5173/invitations/accept#token=secret-token");

  @Test
  void byDefaultLogsTheMaskedEmailAndTheLinkWithoutToken(CapturedOutput output) {
    new LogEmailSender(false).sendInvitation(EMAIL);

    assertThat(output)
        .contains("invitation email sent to j***@example.com")
        .contains("http://localhost:5173/invitations/accept")
        .doesNotContain("secret-token")
        .doesNotContain("joana@");
  }

  @Test
  void withLogLinksOnItPrintsTheFullLinkForLocalTesting(CapturedOutput output) {
    new LogEmailSender(true).sendInvitation(EMAIL);

    assertThat(output).contains("#token=secret-token").doesNotContain("joana@");
  }

  @Test
  void toStringHidesTheTokenAndTheEmail() {
    assertThat(EMAIL.toString()).doesNotContain("secret-token").doesNotContain("joana@");
  }
}
