# Dexxicon Reader — working notes

Native Android OPDS client (BookOrbit + Grimmory/BookLore backends). See
`.claude/commands/` for repeatable flows (`/build`, `/release`).

## Issue → branch → PR workflow

Whenever the user raises a bug or asks for a change that will result in a code fix, follow
this flow so every change stays traceable back to why it happened:

1. **File a GitHub issue immediately**, before investigating — via `gh` (see "Using gh"
   below), against `dexxfm/dexxicon-reader`.
   - Title: a concise summary of the problem/request.
   - Body: the user's own report in their words (quote it), plus any environment detail
     already known (device, version, server). Add findings as the issue evolves if the
     investigation meaningfully changes the picture before the PR is ready.
   - Label `bug` or `enhancement` as appropriate (repo already has both).
   - One issue per distinct problem — if the user raises several unrelated things in one
     message, file separate issues rather than bundling them.
2. **Investigate and fix** as normal.
3. **Branch off a synced `main`**, never off another unmerged branch (stacking a PR on an
   open PR's branch means its merge target is that branch, not `main` — it can silently
   never reach `main` even after both show as "merged" on GitHub).
   Name it `fix/<issue#>-<slug>` or `feature/<issue#>-<slug>` — the number is what makes an
   issue easy to trace forward to its branch/PR at a glance.
4. **Verify before opening the PR**: `./gradlew :app:assembleDebug testDebugUnitTest` green
   at minimum; a live check on-device/emulator when the bug was behavioral (gesture, UI,
   playback), not just a compile check.
5. **Open the PR** with `gh pr create` — body includes `Closes #<issue#>`, so merging the
   PR auto-closes the issue. Include what the root cause turned out to be and how it was
   verified.
6. **After merge**: delete the local and remote branch (`gh pr merge --delete-branch`, or
   manually). A squash/merge commit often makes `git branch -d`/`-D` warn "not fully
   merged" even though the content landed — that's expected, not a sign something went
   wrong; force-delete once the content's presence on `main` is confirmed (e.g. `git log
   main -- <path>` or re-reading the merged file).

This came from the user directly (2026-09-10) as the standing process for issues they raise
— apply it by default, without asking each time.

## Using `gh`

`gh` (v2.100+) is installed at `C:\Program Files\GitHub CLI\gh.exe` but isn't on PATH in a
fresh shell yet (installed after this session's shells started) — call it by full path, or
just `gh` if a later shell picks up the updated PATH. It's also not logged in via `gh auth
login` (the stored credential-helper token is missing the `read:org` scope that command
insists on), but that doesn't matter: set `GH_TOKEN` from the same token `git credential
fill` already provides, per-invocation, and every `gh` command (issue/PR create, list,
label, etc.) works fine without ever running `gh auth login`:

```bash
TOKEN=$(printf 'protocol=https\nhost=github.com\n' | git credential fill | sed -n 's/^password=//p')
GH_TOKEN="$TOKEN" gh issue create --repo dexxfm/dexxicon-reader --title "..." --body "..." --label bug
GH_TOKEN="$TOKEN" gh pr create --repo dexxfm/dexxicon-reader --base main --head fix/123-slug --title "..." --body "...Closes #123"
```

Prefer this over hand-building JSON payloads and raw `curl` calls to the REST API (the
approach used before `gh` was installed) — it's far less error-prone for multi-line
Markdown bodies.
