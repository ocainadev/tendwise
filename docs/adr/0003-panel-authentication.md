# ADR 0003: Own login in business-service, JWT in HttpOnly cookies

- Status: accepted
- Date: 2026-11-13
- Plan: [panels-plan.md](../panels-plan.md), sections 5.4, 6 and 13 (decision 2)

## Context

v0.1.0 has no users, logins or roles: the two services trust each other with one shared `X-Api-Key`. The panels need three roles (`PLATFORM_ADMIN` for the operator, `OWNER` and `AGENT` per business), one user in several businesses, invitations, and a session that agent-service can also check, because the knowledge base endpoints live there.

## Decision

business-service runs its own login with Spring Security.

- **Passwords:** Argon2id hashes in `core.users`. After 5 consecutive failed logins the account is locked for 15 minutes. Error messages are generic.
- **Access token:** a JWT signed with Ed25519, valid for 15 minutes, carrying the user id, platform role and memberships (business slug and role). It is sent in an `HttpOnly; Secure; SameSite=Lax` cookie.
- **Refresh token:** random, 30 days, stored hashed in `core.refresh_tokens`, rotated on every use. Each login starts a token *family*; reusing an old refresh token revokes the whole family. Disabling a user revokes their refresh tokens, so access ends within 15 minutes.
- **JWKS:** business-service publishes its public key (`GET /auth/jwks.json`). agent-service verifies the same access token with it, so it can serve the knowledge base without proxy code.
- **CSRF:** cookie auth means every state-changing request also needs an `X-CSRF-Token` header equal to a non-HttpOnly CSRF cookie (double-submit). Both services check it.
- **Tenant isolation:** the business comes from the URL slug, checked against the token's memberships before the controller runs. A business the user does not belong to answers `404`.
- **2FA:** TOTP, mandatory for platform admins and optional for owners (T68).

## Alternatives considered

- **A managed identity provider (Auth0, Clerk, Keycloak).** Less code to write, and features such as social login for free. But it adds an external dependency on the login path, a possible monthly cost, vendor lock-in, and (for Keycloak) one more service to run. Spring Security covers what the panels need, and building it is good interview material.
- **Tokens in `localStorage` with an `Authorization` header.** No CSRF to handle, but any XSS can steal the token. HttpOnly cookies keep the token away from JavaScript.

## Consequences

Good:

- No monthly cost and no vendor lock-in; everything runs in docker compose.
- One token works in both services; agent-service only needs the public key.
- Short access tokens limit the damage of a stolen cookie; rotation with reuse detection catches a stolen refresh token.

Bad (accepted trade-off):

- We own security-sensitive code (hashing, lockout, rotation, CSRF, 2FA). Mitigation: Spring Security primitives, tests for every failure case (T31, T32), and a tenant isolation suite that calls every panel route as a user of another business.
- A disabled user keeps access for up to 15 minutes (until the access token expires).
- Key rotation for the Ed25519 key is manual for now (JWKS can publish two keys during a rotation).
