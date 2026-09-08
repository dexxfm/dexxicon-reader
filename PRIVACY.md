# Privacy Policy — Dexxicon Reader

**Last updated: 6 September 2026**

Dexxicon Reader ("the app") is an Android client for self‑hosted book and audiobook
libraries such as BookOrbit and Grimmory / BookLore. This policy explains what data the app
handles and where it goes.

**Short version:** The app has no servers of its own and no analytics. Everything it stores
stays on your device, except the data you explicitly send to the library servers **you**
configure. The developer never receives any of your data unless you choose to email a
crash or problem report.

---

## Who is responsible

The app is developed and published by the Dexxicon Reader developer ("we", "us").
Contact: **<INSERT CONTACT EMAIL>** *(replace before publishing; the Google Play listing
requires a working address)*.

## What the app does with your data

### Data you provide

- **Server details** — the address, username, and password (or SSO session) for each
  library server you add. Passwords and sync tokens are encrypted on your device using a
  hardware‑backed key (Android Keystore, AES‑GCM) and are never written to logs or backups
  in plaintext. Everything else (server address, username, display name) is stored in the
  app's private database.

### Data created as you use the app

Stored **only on your device**, in the app's private storage:

- Reading and listening position, and per‑book reading preferences
- Highlights and notes you create
- Books you choose to make available offline (the downloaded files)
- App preferences (theme, Wi‑Fi‑only downloads, audiobook options, etc.)

### Data sent to your library servers

The app communicates **only with the servers you add** (and, for SSO, with the identity
provider that server points you to). To those servers it sends:

- Your credentials or SSO session, to sign in
- Requests to browse your library, and to stream or download your books
- Your reading/listening position and your highlights, so they sync with the server's web
  reader and your other devices
- For **generic OPDS servers that use KOReader (kosync) sync only**: a device label (your
  device model name, e.g. "Pixel 7") and a random device identifier derived from your
  device and salted so it cannot be traced back, as required by the kosync protocol. This
  is not sent to BookOrbit or Grimmory servers, which use their own sync API.

We do not control your library servers or your identity provider. Their handling of this
data is governed by their own policies and your configuration of them.

### Data we collect

**Only what you choose to send.** The app contains no analytics, advertising, or tracking
SDKs, and makes no automatic connections to the developer or any third party.

If the app crashes, it saves a report **on your device**. On the next launch it offers to
send that report; you see its full contents first (the error, your app version, device
model, Android version, locale, and memory figures — no account details, library contents,
or server addresses), and it is sent only as an email **from your own mail app, if you
choose to send it**. You can also send a problem report at any time from
**Settings → Feedback → Report a problem**. Reports go to the contact address above.
Nothing is uploaded in the background and there is no server collecting them.

## Permissions

| Permission | Why |
|---|---|
| Internet / Network state | Connect to your library servers; detect Wi‑Fi vs. mobile data for the Wi‑Fi‑only download option |
| Foreground service (media playback) | Keep audiobooks playing with lock‑screen and notification controls when the app is in the background |
| Foreground service (data sync) | Continue a book download you started if you leave the app |
| Post notifications | Show playback and download notifications |
| Wake lock | Prevent the device sleeping mid‑download or during playback |

The app does not request access to your contacts, location, camera, microphone, files
outside its own storage, or any other personal data.

## Data sharing

We do not share, sell, or transfer any data to anyone. The only data transmission is
between the app and the servers you configure.

## Data retention and deletion

- All app data lives in the app's private on‑device storage. **Uninstalling the app
  permanently deletes it**, including saved credentials and downloaded books.
- You can remove an individual server (and its stored credentials) or delete a downloaded
  book from within the app at any time.
- To remove reading progress or highlights from a server, use that server's own interface.

## Children

The app is not directed at children and collects no personal information from anyone.

## Changes to this policy

If this policy changes, the "Last updated" date above will change and the new version will
be published at the same location. Material changes will be noted in the app's release
notes.

## Contact

Questions about this policy: **<INSERT CONTACT EMAIL>**.

---

## Appendix — Google Play "Data safety" answers

For the Play Console Data safety form, the accurate answers are:

- **Does your app collect or share any of the required user data types?** — No.
  *(The app transmits your credentials and reading data only to the self‑hosted servers you
  choose; this is on‑device configuration of a third‑party service, not collection by the
  app developer. If Play's review requires it to be declared, list "App activity → other"
  and "Personal info → user IDs / email" as **collected but not shared**, **not processed
  by us**, and **required** for the app to function, encrypted in transit.)*
- **Is all user data encrypted in transit?** — Yes (HTTPS, unless you configure a plain
  HTTP server yourself).
- **Do you provide a way for users to request data deletion?** — Data is deleted on
  uninstall and can be cleared in‑app; server‑side data is managed on your server.
