# Bento: notes for Claude Code

Bento is an Android app (Kotlin, Jetpack Compose, Material 3) for jailbroken PlayStation consoles. It sends payloads,
installs packages, browses the console's files over FTP, edits game memory through ps5debug-NG, and more. The repo is
`DubDeezo-hub/Bento-Beta`, public, MIT license. The README and docs/GUIDE.md are the user-facing description; this file
is for working on the code.

## The owner's setup (what "works" means)

- **PS5:** firmware 10.40, Relapse jailbreak, etaHEN. This is the main target.
- **PS4:** jailbroken, GoldHEN. Supported too.
- **Phone:** the owner tests on their own Android phone, installing the APK from the GitHub release.
- **Builds:** the owner has no local Android build habit; GitHub Actions builds every APK (below).

## Hard rules

1. **Never contact Sony.** No request from the phone, and nothing Bento tells the console to do, may reach a Sony server
   (update servers, PSN, title metadata). The owner wants no updates pushed and no bans. Game update checks use only the
   community patch sites orbispatches.com (PS4 titles) and prosperopatches.com (PS5 titles), sending a title ID and
   nothing else. Nothing is ever downloaded or installed from them automatically.
2. **Never change `applicationId` (`com.example.pssender`) or the signing key (`app/pssender-debug.keystore`).**
   Android would treat the app as a different one and installed copies could no longer update. The old name stays in
   `namespace`, `Theme.PSSender` and intent action strings for the same reason; nobody sees it in the app.
3. **No user-visible text in code.** Every text is a string resource in `res/values/strings.xml`, read with `str(R.string.x)`
   in Compose or `AppText.get(R.string.x, ...)` elsewhere. Every new key must also go into all 52 translations (below).
4. **Plain words.** Texts, comments and commit messages are written in everyday language ("the console", "the phone",
   "what went wrong"), not jargon. Comments explain why, not what.
5. **Push straight to main is allowed**, but a push to main is a release (the owner's phone offers it as an update).
   For anything big, push to a branch first, wait for the Phone test to pass, then fast-forward main.

## Building and CI

There is no local Android SDK requirement for working on this repo; GitHub Actions builds and tests everything.

- **Build APK** (`.github/workflows/main.yml`): on every push to `main`. Runs `gradle assembleDebug` (Gradle 8.9,
  JDK 17) and publishes the APK as a GitHub release. The version is the run number (`versionCode = GITHUB_RUN_NUMBER`,
  `versionName = "1.<n>"`), so each build is an update of the last.
- **Phone test** (`.github/workflows/tests.yml`): on every push to any branch (not for `.md`-only changes).
  It checks the translations (`check_strings.py`), builds, then runs `.github/scripts/ui_smoke.sh` on an Android 14
  emulator. That script installs the previous release and updates it, does a fresh install, opens every tab,
  rotates the screen, leaves and returns, and opens every language. Results, screenshots and logcat are in the run's
  summary and in the `phone-test-results` artifact. Landscape on a short screen (640x320) must still show the
  bottom bar.
- Locally, with Android Studio or the SDK: `gradle assembleDebug`. minSdk 29, target/compile 34.
- `gh run list -R DubDeezo-hub/Bento-Beta -L 5` shows CI state.

## Code layout

One flat package, `app/src/main/java/com/example/pssender/`. Main pieces:

| Area | Files |
|---|---|
| App shell, tabs, startup | `MainActivity.kt`, `Chrome.kt`, `Theme.kt` (AppLook: black theme, text size), `Texts.kt` (AppText), `Language.kt` (in-app language picker), `Welcome.kt`, `Tips.kt`, `Help.kt`, `Icons.kt` |
| Consoles | `Profiles.kt` (saved consoles, ConsoleType PS5/PS4 and their default ports), `Scan.kt`/`Find.kt` (find consoles on the network), `Firmware.kt` (console firmware, payload firmware claims), `Diagnose.kt` ("What's wrong?" check) |
| Payloads | `Net.kt` (payload send, installer request, FTP helpers), `Shelf.kt` (saved payloads, verified SHA-256), `Store.kt` + `StoreIndex.kt` (Get payloads: built-in list, `payloads/index.json`, the itsPLK community list, GitHub search), `Chains.kt`/`ChainUi.kt`/`ChainService.kt` (payload chains, auto-run on connect) |
| Install packages | `Serve.kt`, `ServeService.kt`, `ServeSupport.kt`, `ServeState.kt`, `FileServer.kt` (the phone serves a .pkg over HTTP and asks the console's installer to fetch it), `PkgCheck.kt` (package against what is installed), `Queue.kt` |
| Files (FTP) | `Files.kt`, `FilesLogic.kt`, `TransferService.kt`, `TransferState.kt`, `ConsoleEdit.kt` (etaHEN settings editor, text editor), `ConsoleExtras.kt` (game sizes, capture sync, shelf checks), `ConsoleTools.kt` + `ConsoleMeta.kt` (installed games and apps, update check), `GameUpdates.kt` (patch sites) |
| Memory | `Memory.kt` (UI), `MemorySession.kt` (state, undo, text search), `Ps5Debug.kt` (ps5debug-NG protocol client), `Scanner.kt`, `Pointers.kt`, `CheatFiles.kt`, `FreezeService.kt`, `MemoryText.kt`, `MemoryLearn.kt` (lessons and coach), `PracticeConsole.kt` (a pretend ps5debug-NG server inside the app, for the practice game), `Klog.kt` (console log) |
| Web | `Web.kt`, `Browser*.kt`, `Links.kt`, `HttpSource.kt` |
| Other | `About.kt` (settings, debug info), `Backup.kt` (backup/restore, weekly auto backup), `Update.kt` (new-release banner), `Tally.kt`, `Unfinished.kt` |

Other repo files:
- `payloads/index.json`: Bento's own curated payload list, read by every installed app (raw.githubusercontent.com,
  main branch). Editing it changes the store for everyone without an app update.
- `fake_console.py`: a pretend console for testing without hardware (see below).
- `docs/GUIDE.md`, `README.md`: user docs. Keep them in step with features.

## Strings and translations

- English: `app/src/main/res/values/strings.xml` (about 1,100 keys). 52 translations in `values-*`
  (the list is `res/xml/locales_config.xml` and `AppLanguage.TAGS` in `Language.kt`; keep the two in step).
  Android's old codes are used for folders: Hebrew `values-iw`, Indonesian `values-in`.
- `app_name`, `credit_name`, `service_ftp` are `translatable="false"`.
- Rules for every string: apostrophes written `\'`; `%1$s` blanks exactly as in English; no HTML.
- After adding or changing keys, translate them into every language, then:
  - `python3 .github/scripts/rtl_marks.py` (Arabic, Persian, Hebrew, Urdu: a right-to-left mark for lines that start
    with a Latin word)
  - `python3 .github/scripts/check_strings.py` (must report 0 problems; CI runs it too)

## Testing without a console

- `fake_console.py` (needs `pip install pyftpdlib`) answers on the console's ports from a PC or from Termux on the phone:
  installer 12800, payload loader 9021 and 9090, FTP 2121, debugger 7744 (`FAKE_DEBUG_PORT`; Android apps can't open
  ports below 1024), console log 3232 (`FAKE_KLOG_PORT`). It has two pretend games, saves, screenshots, a sample .pkg,
  a cheat file and a pretend running game (pid 91) for memory scans. In the app, set the console's address to the PC's
  IP (or 127.0.0.1 in Termux) and the Memory tab's port to 7744.
- The Memory tab's practice game (`PracticeConsole.kt`) runs a pretend ps5debug-NG on 127.0.0.1 inside the app; it
  is also the easiest way to test the debugger client code on a JVM.

## Facts checked against etaHEN's source (github.com/etaHEN/etaHEN, Nov 2026)

| Service | Port / place | Note |
|---|---|---|
| ELF loader | 9021 | etaHEN requires it running |
| FTP (built in) | **1337** | `FTP=1` by default. Bento's PS5 default is 2121 (the ftpsrv payload's port); Diagnose and the network scan also try 1337 |
| Package installer, DPI v2 (HTTP, `POST /upload`) | 12800 | only when `DPI_v2=1` (default 0). This is what Bento uses |
| Package installer, DPI v1 (raw socket) | 9090 | when `DPI=1` |
| Kernel log | **9081** | when `Klog=1`. Bento's console log defaults to 3232 (ps5debug-NG's, not verified) |
| ps5debug | 744 | only when `PS5Debug=1` (default 0) |
| Settings file | `/data/etaHEN/config.ini` | one `[Settings]` section, `key=value`. Every key in `EtaHenConfig.KNOWN` exists in etaHEN; `StartOption` 0 none, 1 home, 2 settings, 3 toolbox, 4 itemzflow |

## Where the work stands

Done and released: everything in the README, including the big update (practice game and lessons in Memory,
game update check, storage view, capture sync, PS4 cheats, verified shelf, weekly backups, etaHEN settings editor,
batch install, text editor, folder download as zip, black theme and text size, language picker, payload discovery),
fully translated.

In progress (a "piece by piece, make sure everything works" pass the owner asked for):
1. **Port defaults vs etaHEN** (table above). Decide: PS5 FTP default 1337 instead of 2121 for new consoles; console
   log to try 9081 (etaHEN) as well as 3232. Saved consoles keep their own ports either way.
2. **Unit tests**: there are none yet in `app/src/test`. Plan: JUnit (`testImplementation("junit:junit:4.13.2")`) for the
   pure logic (`FirmwareSupport.claims`, `ConsoleFirmware.normalize`, `StoreIndex.parseCurated/merge/parseCommunity`,
   `GameUpdates.keyIn/parsePatches/newest`, `PkgCheck.notes/compareVersions`, `EtaHenConfig.parse/apply`,
   `TextSearch`, `AppLanguage.match`) and the debugger client (`Ps5Debug` against `PracticeConsole`), run with
   `gradle testDebugUnitTest` in both workflows.
3. **Phone test against the fake console**: run `fake_console.py` on the CI machine (the emulator reaches it at
   10.0.2.2) and have `ui_smoke.sh` connect and use features, not just open tabs.
4. **Review of the newest code** for bugs: Store/StoreIndex, Diagnose, GameUpdates, ConsoleEdit, ConsoleExtras, zip
   download, batch install, Memory undo/text search/lessons, Language, Theme, Backup, auto chain.
5. **Checks only real hardware can answer**, to walk the owner through on the phone and PS5: payload send on 9021,
   FTP port in use, installer (DPI_v2) on 12800, ps5debug on 744 and the practice game on a real phone, the game
   update check against the patch sites, the etaHEN settings editor saving config.ini, folder zip download, capture sync.

On hold (owner's choice): auto-jailbreak, a Quick Settings tile, a home-screen widget.

Known limits: free space on the console can't be read over FTP (the storage view shows per-game sizes only).
