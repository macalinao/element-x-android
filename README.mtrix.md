# Element X Android, mtrix fork

A small patch set on top of upstream Element X Android releases that renders
`com.ianm.mtrix` rich-message payloads natively in the timeline. The spec and the
reference (Element Web) renderer live in
[macalinao/mtrix](https://github.com/macalinao/mtrix) (`docs/rich-messages.md`,
`element/`).

Branch `mtrix` = latest upstream release tag + the `mtrix:` commits. App id
`io.element.android.x.mtrix` ("Element X mtrix"), so it installs next to the
official app.

## What it does

- Bot messages (`m.text`/`m.notice`) from the allow-list in
  `features/mtrix/.../MtrixConfig.kt` (`@claude`, `@tala`, `@holo`, `@revi`, `@ploy`
  on `matrix.pilipili.stream`) that carry a `com.ianm.mtrix` key (or the legacy
  `stream.pilipili.rich`) get the payload's blocks drawn **below** the normal body.
  Everyone else's payload is ignored. The body always renders, so parse errors and
  unsupported blocks fall back to it.
- Blocks: `buttons`, `chips`, `table` (tap header to sort), `chart`
  (line/area/bar/sparkline on a Compose Canvas, tap for values), `progress`,
  `details`, `code`, `diff`. Others (`sources`, `card(s)`, `countdown`, `event`,
  `place`, `ticker`, `weather`) are skipped.
- Buttons can only send your own reaction with the exact key, or an `m.text`
  reply to the same event with the exact body. Tapping a button that is already
  selected does nothing; it never redacts. Counts ignore the bot's own seeded reactions.
- Detection: Element X's mapped content drops unknown keys, so the payload comes
  from the raw event JSON the SDK keeps for "View source"
  (`EventTimelineItem.lazyProvider.debugInfo()`: the decrypted `originalJson`, and
  `latestEditJson` whose `m.new_content` replaces it). That call only runs in the
  background timeline factory, and only for allow-listed senders.

## Files

New:

- `features/mtrix/`: parser, config, action performer, Compose UI, unit tests
- `features/messages/impl/.../timeline/mtrix/MtrixTimelineGlue.kt`
- `flake.nix`, `flake.lock`, `scripts/rebase-upstream.sh`,
  `.github/workflows/mtrix-rebase.yml`, this file

Touched upstream files (one-line hooks, marked `mtrix fork`):

- `features/messages/impl/build.gradle.kts`: depends on `:features:mtrix`
- `.../timeline/model/TimelineItem.kt`: `Event.mtrix` field (default null)
- `.../timeline/factories/event/TimelineItemEventFactory.kt`: fills it
- `.../timeline/components/TimelineItemEventRow.kt`: content goes through `MtrixEventContent`
- `.../timeline/TimelineEvent.kt`: `MtrixButtonTapped`
- `.../timeline/TimelinePresenter.kt`: handles it
- `plugins/src/main/kotlin/config/BuildTimeConfig.kt`: app id and name
- `app/build.gradle.kts`: optional `mtrix` signing config
- `.gitignore`: `/dist/`

## Build

```sh
nix develop -c ./gradlew :features:mtrix:testDebugUnitTest :app:assembleFdroidRelease
```

The F-Droid flavour avoids Google services. Push goes through UnifiedPush (or
background sync), because the gplay flavour's Firebase config is tied to
upstream's app id. APKs end up in `app/build/outputs/apk/fdroid/release/`.
`scripts/rebase-upstream.sh` copies them to `dist/` (gitignored).

## Signing

The keystore is at `~/.config/element-x-mtrix/keystore.jks` (alias `mtrix`).
The password is in `keystore.password` next to it (mode 600), and both are
backed up in Bitwarden as "element-x-mtrix APK signing keystore". Gradle reads
these settings from `~/.gradle/gradle.properties`:

```properties
mtrix.signing.storeFile=/home/igm/.config/element-x-mtrix/keystore.jks
mtrix.signing.storePassword=...
mtrix.signing.keyAlias=mtrix
mtrix.signing.keyPassword=...
```

They can also come from the `MTRIX_SIGNING_STORE_FILE`,
`MTRIX_SIGNING_STORE_PASSWORD`, `MTRIX_SIGNING_KEY_ALIAS` and
`MTRIX_SIGNING_KEY_PASSWORD` env vars. Without them, builds fall back to
upstream's in-repo debug key. Check a signature with
`nix develop -c bash -c '$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs dist/*.apk'`.

## Rebasing onto a new upstream release

```sh
scripts/rebase-upstream.sh          # fetch upstream tags, rebase onto the latest vX.Y.Z, test, build, copy to dist/
scripts/rebase-upstream.sh --push   # same, then force-push (with lease) mtrix to origin
```

Before rebasing, the script saves the old branch as `mtrix-backup/<old-tag>-<time>`.
On a conflict it aborts the rebase, lists the conflicting files, and exits with
code 2. The hooks are one-liners, so conflicts should be rare and easy to fix.

`.github/workflows/mtrix-rebase.yml` does the same every week (Monday 03:17 UTC)
or on demand, without any secrets. A clean rebase that passes the tests and the
debug build goes to `mtrix-next`, and the workflow opens an issue. A conflict
also opens an issue. The workflow never rewrites `mtrix`. Actions and issues
are off by default on forks, so turn them on in the repo settings for this to
run.
