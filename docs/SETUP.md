# Connecting a server

## Password sign-in

Add your server URL, username, and password, then **Test connection**. Works with both
BookOrbit and Grimmory using your normal account. Include the scheme and port exactly
(e.g. `http://192.168.1.5:6060` for a LAN Grimmory instance — plain HTTP is allowed).

## SSO / OIDC sign-in

The app can sign in through your identity provider (Authentik, Keycloak, …) instead of a
password. Tap **Sign in with SSO** on the add-server screen — it discovers the provider
from the server and opens a browser tab to log in.

### One-time provider setup

The app uses a native redirect URI:

```
net.dexxicon.reader://oauth2redirect
```

Add that exact string to **both**:

1. **Your identity provider** — the OAuth2/OIDC application's list of allowed redirect URIs
   (in Authentik: *Applications → Providers → your provider → Redirect URIs*). It must be a
   public client (PKCE, no client secret).
2. **The server's OIDC settings**, if it maintains its own allow-list:
   - Grimmory: *Settings → OIDC* (BookLore accepts the app's `/api/v1/auth/oidc/mobile/callback`).
   - BookOrbit: *Admin → OIDC* provider config.

After that, SSO sign-in completes without any further configuration and the app stores the
resulting refresh token (encrypted, on-device) so it can renew the session silently.

### Notes

- BookOrbit access tokens are short-lived; if its OIDC flow doesn't return a refresh token
  you'll be asked to sign in again when the session expires.
- Grimmory (BookLore) returns a refresh token and renews silently.
