<p align="center">
  <img src="../bento-icon.png" width="128" height="128" alt="Bento app icon: a cyan and black bento box with five compartments">
</p>

<h1 align="center">Bento: the full guide</h1>

<p align="center">
  <b>Send games, payloads and files from your Android phone to a jailbroken PS4 or PS5.</b><br>
  by DubDeezo
</p>

This is the complete manual: every function, what Bento does behind it, and the details for developers. The short version is the [README](../README.md).

Bento is an Android app that talks to the services a jailbroken PS5 or PS4 already runs. On a PS5 that is etaHEN's package installer and ELF loader with an FTP server; on a PS4 it is GoldHEN's loader and FTP server, with flatz's Remote Package Installer for packages. From one screen you can install game packages, send a payload (or a whole chain of them), upload and manage files, or browse the web and push a download straight to the console. Each saved console is marked as a PS5 or a PS4, and the app uses the right services and ports for it. Bento can also look for the console on your Wi-Fi, so you don't have to type its address. Nothing has to be installed on the console for Bento itself. It needs Android 10 or newer.

The name and the icon are a bento box: several different things, each in its own compartment.

> **Status:** Bento builds on GitHub Actions, runs on an Android phone and has been tried against a fake console ([`fake_console.py`](../fake_console.py)). FTP uploads have been confirmed against the author's real PS4 on GoldHEN; the rest of the PS4 path and the whole PS5 path have **not been confirmed on a real console yet**. The author's PS5 runs firmware 10.40 with etaHEN. The newest features (see [Status and known limits](#status-and-known-limits)) have been checked on a computer but are not confirmed on a phone yet.

## Contents

- [What Bento does](#what-bento-does)
- [Before you start](#before-you-start)
- [Get the app](#get-the-app)
- [First run](#first-run)
- [How it works](#how-it-works)
  - [The console card](#the-console-card) · [Find my console](#find-my-console) · [Consoles](#consoles) · [Install tab](#install-tab) · [Install from this phone](#install-from-this-phone) · [Payload tab](#payload-tab) · [Upload tab](#upload-tab) · [Files tab](#files-tab) · [Web tab](#web-tab) · [Memory tab](#memory-tab) · [Share sheet](#share-sheet) · [Transfers](#transfers) · [Errors that say what to do](#errors-that-say-what-to-do) · [About, updates and backup](#about-updates-and-backup) · [Languages and look](#languages-and-look)
- [Under the hood](#under-the-hood)
- [Build, signing and releases](#build-signing-and-releases)
- [Testing without a console](#testing-without-a-console)
- [Troubleshooting](#troubleshooting)
- [Status and known limits](#status-and-known-limits)
- [Privacy and security](#privacy-and-security)
- [Responsible use and license](#responsible-use-and-license)
- [Credits](#credits)

## What Bento does

| Tab | What it is for |
| --- | --- |
| **Install** | Send package links to the console's installer (one, or a whole list pasted at once), or share packages straight from your phone so the console downloads them over Wi-Fi. |
| **Payload** | Send `.elf` and `.bin` payloads to the console's ELF loader. Keep the ones you use often on a shelf and send them with one tap, or line several up in a **chain** that you can start from the app icon. |
| **Upload** | Upload files from your phone to the console over FTP, in the background. |
| **Files** | Browse the console's files over FTP: open folders, select several things at once, download whole folders to the phone, rename, delete, create folders, upload here, sort, search, and pin the folders you use often. |
| **Web** | A built-in browser with tabs, ad blocking, bookmarks, find in page and a desktop-site switch. Any download, or any link you press for a while, can go to the console's installer, to the console over FTP, to the payload shelf, or to the phone. |

Across the app:

- A **console card** shows live which of the console's services are answering. **Find my console** looks for the console on your Wi-Fi when you don't know its address.
- **Several consoles**, each marked as a PS5 or a PS4, with its own address, ports, FTP login and color.
- A **background queue** for FTP transfers: one at a time, with speed and time left, **pause and resume**, an optional **speed limit**, a summary when a run is over, a notification, automatic resume after a dropped connection, and a card that offers the transfers again if the app closed before they were done.
- **Errors that say what to do:** a problem message has a **What can I do?** button with the likely cause and a few things to try.
- A **share-sheet target**: share a link from any app to Bento to send it to the console.
- **About & backup:** a notice when a newer build is on the Releases page, a backup file for your consoles, bookmarks and payloads, and a short report you can paste into a bug report.
- **53 languages** (English plus 52 translations), right-to-left languages included, and dark and light themes.

## Before you start

You need:

- an Android phone with Android 10 or newer,
- a jailbroken console: a PS5 running etaHEN, or a PS4 running GoldHEN,
- the phone and the console on the same Wi-Fi network (not a guest network),
- the console's IP address. On a PS5: **Settings > Network > Connection Status > View Connection Status**. On a PS4: **Settings > Network > View Connection Status**. Or let [Find my console](#find-my-console) look for it.

Bento uses up to three services on the console. You only need the ones for the tabs you use, and the console card shows which are answering.

| Service (default port) | Bento uses it for | On a PS5 | On a PS4 |
| --- | --- | --- | --- |
| Package installer (`12800`) | The Install tab and the Web tab's "Send link to the console installer" | etaHEN's Direct Package Installer V2 | flatz's Remote Package Installer |
| ELF loader (PS5 `9021`, PS4 `9090`) | The Payload tab and payload chains | etaHEN, or ps5-payload-dev's `elfldr` | GoldHEN's loader |
| FTP server (`2121`, or `1337` for etaHEN's own) | The Upload and Files tabs, and FTP relay from the Web tab | ps5-payload-dev's `ftpsrv`, or etaHEN's built-in FTP | GoldHEN's FTP |

"Install from this phone" works the other way round: the console downloads from a small server on your phone (port 8000 if it is free).

## Get the app

**Download.** Open this repository's [Releases](https://github.com/DubDeezo-hub/Bento-Beta/releases) page and download the newest `Bento-1.N.apk`. Open it on your phone and, if Android asks, allow installing apps from that source. A new release installs over the old one and keeps your settings, because every build is signed with the same key. Bento also checks the Releases page for you and tells you when a newer build is there (see [About, updates and backup](#about-updates-and-backup)); installing it is still up to you.

**Build it yourself (works from a phone).**

1. Fork this repository on GitHub, or copy its files into a repository of your own. In a fork, open the **Actions** tab once and allow workflows to run.
2. In the **Actions** tab, choose **Build APK** and tap **Run workflow**. It also runs on every push to `main`.
3. When it finishes, the APK is on the Releases page (and on the run as the `bento-debug-apk` artifact).

More in [Build, signing and releases](#build-signing-and-releases).

## First run

1. Open Bento and tap the card at the top. Enter the console's IP address and tap **Done**, or tap **Find my console** to let Bento look for it on your Wi-Fi.
2. Look at the three lights on the card: **Installer**, **Loader** and **FTP**. A filled light means the service answers, a hollow ring means it doesn't. Start what is missing on the console; the card names it.
3. Pick a tab at the bottom and use it. On Android 13 and newer, allow notifications when asked, so background transfers can show their progress.

What is on the screen: the header (app name, "by DubDeezo" and the selected console; tap the name for **About & backup**), a notice when a newer version is out, the console card, the current tab, a one-line status message (tap it to hide it), then, pinned above the bottom bar so they show on every tab, a card for unfinished transfers (only when there are some), the card of a running payload chain and the transfer card, and finally the bottom bar.

## How it works

**Help on every screen.** A small **?** next to an option opens a short how-to for it. Settings that are set once and then left alone (ports, FTP login) are folded into a **Settings** or **FTP settings** row at the bottom of their tab; tap it to open it. On the first start a **welcome page** explains what Bento is and what each tab does; open it again from the About window.

Every function below is described twice: what you do, and what Bento does behind it. The code files that hold each part are listed in [Under the hood](#under-the-hood).

### The console card

The card at the top says whether the console can be reached.

- While Bento is on screen and an address is set, it checks every 6 seconds. It opens a TCP connection to each of the three service ports at the same time (1.5 seconds each) and closes it again. Nothing is sent.
- All three answer: **Ready**. Some answer: **Partly ready**, and the card names what is off. None answer: **Can't reach the console**.
- Checks pause while a transfer runs, because the console is busy, and stop when the app is not on screen.
- A light only means the port accepts connections. It does not prove the service will accept your request.
- On the Files and Web tabs the card is shorter, to leave room for the list and the page: it shows the overall state and the address but not the three service lights, so a console that is partly ready is shown with what is off (for example `Partly ready · off: FTP`).
- When some or all services are off, the full card has a **What's wrong?** button (see [Connection check](#connection-check)); the address window has one too.
- Tap the card to change the address. With no address, or when nothing answers at it, the full card (on the Install, Payload and Upload tabs) also has a **Find my console** button, and the address window has one too.

### Find my console

When you don't know the console's address, or it has changed (a router can hand out a new one after a restart), Bento can look for the console on your Wi-Fi.

1. Tap **Find my console**. The button is on the console card while there is no address or nothing answers at it, in the window where you type the address, and on the help page of a "Can't reach the console" message.
2. Bento works out which network the phone is on and tries to connect to every address in it. For each address it tries the console's ports (the ones saved for the selected console, plus the usual 12800, 9021, 9090, 1337 and 2121), up to 64 attempts at the same time, and gives each attempt 0.45 seconds. A progress bar shows how far it is, and addresses appear in the list as they answer.
3. Each address that answered is listed with the services that answered on it (Installer, Loader, FTP). The likeliest console comes first: an address where the installer or the loader answers is ranked above one that only has an FTP server. Tap **Use** to save the address for the selected console. An address that is saved already is marked, and one that belongs to another of your consoles says so.
4. **Stop** ends the scan early and **Scan again** starts another. If nothing was found, the window says what to check: the console fully on (not in rest mode) with etaHEN (PS5) or GoldHEN (PS4) started, and the phone and the console on the same Wi-Fi.

What the scan does and doesn't do:

- It only runs when you tap the button, and it only looks at the private network your phone is connected over Wi-Fi: the /24 around the phone's own address (a smaller network is scanned whole), at most two networks, Wi-Fi first. It never uses the mobile network. If the phone isn't on Wi-Fi, the window says so.
- It opens a connection to a port and closes it again. Nothing is sent, and nothing leaves your local network.
- A device that only has an FTP server open could be anything, so check the address before you trust it.

### Connection check

**What's wrong?** looks closer than the card's lights and says what to do, one finding per line with a colored dot (green fine, grey information, amber attention, red a problem).

- It checks whether the phone is on Wi-Fi and whether the console's address is in the phone's network (a different network usually means a changed address or a guest network), pings the address, and opens a connection to each service's port (1.5 seconds each, all at once): the installer, the loader, FTP and the Memory tab's debugger (port 744). Nothing is sent.
- A connection that is refused means the console is on but that service isn't running; no answer at all means the console is off, asleep, at another address, or kept apart by the router.
- For each service that doesn't answer it also tries the other ports that service often uses (loader 9020/9021 on a PS5 and 9090 on a PS4, FTP 1337 for etaHEN and 2121 for ftpsrv and GoldHEN). When one answers, **Use port N** saves it for this console.
- Other fixes are one tap too: **Get payloads** (for a missing FTP server or debugger) and **Find my console** (when nothing answers).
- **Copy debug info** copies the same report as About (see below), including a short English summary of this check.

### Consoles

Tap the console name at the top right to open the switcher.

- Each console is set as a **PS5 or a PS4** (the switch is in the console's editor) and keeps its own name (up to 16 characters), IP address, installer port, loader port, FTP port, FTP user, FTP start folder and one of six colors: blue, orange, green, pink, purple or teal. The installer and FTP ports default to 12800 and 2121; the loader port defaults to 9021 on a PS5 and 9090 on a PS4. The FTP user defaults to `anonymous` and the start folder to `/data`. Changing a console's type moves any port still left at the old default to the new default. The whole app takes the color of the selected console, and a running transfer says which console it belongs to.
- You can add, rename, recolor and delete consoles (never the last one), and pick the app's **Language**, or open **About & backup**, from the same dialog.
- Settings stay on the phone. The FTP password is not saved: it is kept in memory only, so it is forgotten when the app closes. The default login is `anonymous` with no password.

### Install tab

Type or paste one or more package links into **Package links** and tap **Send**. Each link has to be a direct link the console can open by itself: no login, and reachable from the console's network.

- **One link per line, or paste a whole message.** The box takes any text, and Bento picks the web links out of it (addresses that start with `http://` or `https://`). A full stop, comma or bracket that belongs to the sentence rather than to the link is dropped, a link that appears twice is sent once, and anything that isn't a link is ignored. A note under the box says how many words were left out for that reason. At most 50 links are sent in one go.
- **Paste** adds the links found on the clipboard to the box (links that are in it already are not added twice). **Clear** empties the box. What is in the box stays when you switch tabs.
- **Send.** The button shows how many links will go, for example **Send (3)**. The links go to the installer one after another. A link the installer doesn't accept doesn't stop the others; a console that can't be reached does, because every other link would only run into the same wait. While it sends, the button reads **Cancel**; that takes effect before the next link, and the one being sent is finished.
- **Accepted links leave the box**, so what is left still needs sending, and they join **Recent links**: the last 10 accepted links, newest first, as buttons named after the file at the end of the link. Tap one to put it back in the box; **Clear list** forgets them all. Links accepted from the Web tab's chooser or the share sheet join the list too. The list stays on the phone, in the app's private settings.
- **The result.** For one link, the status line shows the installer's own answer. For several it says, for example, `Accepted: 2 of 3 links`, then what went wrong with the first link that failed, then how many were not sent.

For each link, Bento posts it to the console's installer (the installer port under **Settings** at the bottom of the tab, default 12800) and reads the answer. It tries two request styles, in this order:

1. **etaHEN style** (a PS5's installer). `POST /upload` as a multipart form with a `url` field. If the reply contains `SUCCESS:`, the installer accepted it.
2. **Remote Package Installer style** (a PS4's installer). `POST /api/install` with `{"type":"direct","packages":["<link>"]}`. The link goes as it is, not URL-encoded, the way flatz's README shows it, except that an `https` link is sent as `http`. If the reply contains `success`, the installer accepted it.

If neither works, the status line shows both HTTP codes and the first 80 characters of each reply, for example `Not accepted (upload 200: …; api 404: …)`, so you can see what the console really said. When a message has a likely cause, a **What can I do?** button explains it (see [Errors that say what to do](#errors-that-say-what-to-do)).

**Check** (next to Paste and Clear) shows what each package is before you send it: its icon, name, title ID, version, kind (game, update or add-on), the lowest firmware it needs and its content ID. For a web link Bento reads only a few small pieces (the package header, its file table, `param.sfo` and `icon0.png`) with byte-range requests, so a 40 GB package costs a few kilobytes; a site that only sends whole files is reported instead. **Check a package on this phone** does the same for a file you pick. PS4 packages (`\x7FCNT`) are fully read. PS5 packages keep this encrypted, so only their size is shown.

When the console's FTP server answers, the check also compares each package with what is installed (the same list as Games & apps): **Not on this console yet**, **Already installed (v1.02)**, **This is an update: v1.02 on the console, v1.05 here**, an older version than the one installed, or an update or add-on for a game that isn't installed. A PS4 package checked for a PS4 also says when it needs newer firmware than the console has, and a PS5 package checked for a PS4 says that it can't be installed there.

Bento tries both styles on either console, so one app works on a PS5 and a PS4. Both styles come from the public documentation of existing installer tools (see [Credits](#credits)). The replies a real console gives have not been confirmed yet (see [Status and known limits](#status-and-known-limits)).

### Install from this phone

For packages that are stored on your phone. Nothing is copied to the console first: the console downloads each file straight from the phone over Wi-Fi.

1. Under **Install from this phone**, tap **Choose packages** and pick one or more files, or **Choose a folder** to take every `.pkg` in a folder and the folders inside it (up to 50, in name order). Bento needs each file's exact size, so a file whose size can't be read is marked and can't be shared. **Installed from this phone before** lists the last 8 sets you shared; **Pick again** picks a set again (files moved or deleted since are left out, and the line says how many).
2. Tap **Start sharing**. A foreground service keeps it running with the screen off (holding the same wake and Wi-Fi locks as a transfer), and a notification (with a stop button) shows it.
3. Bento picks the phone address the console can reach: the phone's address in the console's own network, or else its private Wi-Fi address. When the console address is `127.0.0.1` (testing with `fake_console.py`) it uses the loopback address.
4. It starts a small HTTP server inside the app, listening only on that address, on port 8000 or on another free port if 8000 is taken, and gives every file a link: `http://<phone-ip>:<port>/<token>/<name>`. The token is 24 random hex characters (96 bits), so only someone holding the link can fetch the file. The name is reduced to ASCII letters, digits and `.-_`.
5. Bento sends the links to the console's installer one after another, with the same request as the Install tab.
6. The console downloads from the phone. Each file shows how much of it the console has taken, and the screen shows the combined speed and time left. **Copy link** gives you the link for installing by hand if the console refused the automatic request.
7. Sharing ends when you tap **Stop sharing**, or by itself after 30 minutes with no download activity.

About the server: it answers `GET` and `HEAD`, supports byte ranges (`Range` and `If-Range`, with a strong `ETag`), and accepts up to 16 connections at once (more get a `503` with `Retry-After: 2`). It gives a client 15 seconds to send its request and drops a download that doesn't move for 60 seconds. Progress counts distinct bytes handed out, so a range that is requested again isn't counted twice.

The phone and the console must be on the same network. Guest Wi-Fi often stops devices from reaching each other; if the console never starts downloading, use the main network.

### Payload tab

Payloads (`.elf` and `.bin`) go to the console's ELF loader, which listens on the **Loader port** (default 9021 on a PS5, 9090 on a PS4).

- **Send.** Bento opens a TCP connection to the loader, streams the file in 64 KB pieces, then closes its sending side so the loader sees the end of the file. It reports `Sent 1.5 MB` (or the error).
- **Payload shelf.** Keeps the payloads you use often inside the app, each one tap from **Send**. **Add payload to shelf** copies a file in, and the Web tab's download chooser can save straight to the shelf too. A file may be up to 64 MiB. Adding a file with the same name replaces the old one, but only after the new copy is complete (it is written to a temporary file and then renamed), so a failed copy never damages a working payload. **Remove** deletes it from the shelf only, not the original on your phone. A payload that came from Get payloads and matched a published checksum (GitHub's own digest of a release file, or the PS5 Payloads Mirror's SHA-256) is marked **verified**; tap its name to see its SHA-256 and whether it still matches.
- **Send a file once.** **Choose payload (.elf / .bin)**, then **Send payload**, without saving it.

Sending a payload is a single action with a status line. It is not part of the transfer queue.

#### Get payloads

**Get payloads** finds payloads for the console type of the selected console in three places:

- **Bento's picks.** Well-known payloads Bento uses: on a PS5 etaHEN, ps5debug-NG, ftpsrv, websrv, shsrv, klogsrv and kstuff; on a PS4 ps4debug-NG and GoldHEN. The list is built in, and the app also reads [`payloads/index.json`](../payloads/index.json) from this repository (at most every 6 hours), so a payload can be added or taken off for everyone without an app update. For each one Bento asks the project's own GitHub Releases page for its newest version.
- **From the community list** (PS5). The [PS5 Payloads Mirror](https://github.com/itsPLK/ps5-payloads-mirror) keeps a daily-updated list of 30+ payloads (ps5upload, PKG-Manager, ShadowMountPlus, zftpd, BackPork, the app dumper and more) with the makers' own descriptions, versions and SHA-256 checksums. Bento shows them grouped by kind, with a filter box. **Add to shelf** takes the file from the maker's own release when it is a plain `.elf`/`.bin`, else the list's copy (the list unpacks zips), and keeps it only if it matches the list's checksum.
- **Found on GitHub.** Only when you tap **Search GitHub**: repositories that call themselves PS5 or PS4 payloads (by topic or in their name or description), not forks or archived, with at least 5 stars and pushed in the last 18 months, sorted by stars and minus everything above. Nobody has reviewed these, so the section says so; **Look for a release** checks the newest release for a payload file.

**Add to shelf** downloads the payload onto the shelf; when a release only has a zip, Bento takes the `.elf` or `.bin` out of it. Bento remembers which version came from the store, so the list says **On your shelf: v1.2, the newest** or offers **Update**, and an update replaces the old file. **Page** opens the project's page.

**New payloads and updates.** When the app starts (and when you switch to a console of the other type), Bento reads the lists and checks the payloads you got this way. Under the shelf buttons it says which have a newer version and which payloads are new since you last opened Get payloads; those are marked **New** and listed first. **Check for new payloads** reads the lists again right away. The first start counts everything as seen, so nothing is called new then.

**Firmware.** At the top is the console's firmware. It fills in by itself when the Memory tab connects (the debugger reports it) and can be typed in (**Set**). Bento reads each pick's README and newest release notes, and each community entry's description, for the firmware versions they name (single versions, ranges like 3.00–13.60, families like 10.xx). A payload that names yours says so in green; one that names others but not yours is dimmed with a red note and the range it names; one whose page says nothing says that. Plain servers (ftpsrv, websrv, shsrv, klogsrv) run on any firmware that runs payloads. A long list of exact versions counts as the whole list, so a summary range next to it doesn't hide a missing version. Nothing is blocked: pages can be out of date.

The payloads are made by other people; check that one suits your console before you send it.

#### Chains

A chain sends several payloads from the shelf one after the other, with a pause after each, so a start-up you repeat (a payload, then another one a few seconds later) takes one tap.

- **Make one.** **New chain** asks for a name (up to 24 characters) and then the payloads in order. **Add payload** picks from the shelf (up to 12 payloads in a chain); the arrows move a payload up or down and ✕ takes it out. Each payload has **Then wait (seconds)**: the pause before the next one is sent, from 0 to 300 (3 when a payload is added; the pause after the last payload isn't used). A payload that has left the shelf since is marked **Not on the shelf**. You can keep up to 20 chains; each shows how many payloads it has and how long its pauses add up to. **Edit** changes or deletes one.
- **Run one.** **Send** next to the chain starts it. A foreground service keeps the pauses on time while the app is hidden. The chain card above the transfer card shows which payload is going, or how long the pause lasts, with **Stop**, which ends the chain before its next payload; the notification has a Stop button too. One chain runs at a time. It only starts when the console's address and loader port are set and every payload is still on the shelf; the card says which one is not.
- **If the loader isn't ready.** For a payload after the first, a loader that doesn't answer yet (the payload before may still be starting it) is tried again for up to 15 seconds. That only happens when nothing could have reached the console, so no payload is ever sent twice. Any other problem ends the chain, and the card says at which step and why, and how many payloads went out.
- **From the app icon.** Press and hold the Bento icon and the first four chains show as shortcuts; tapping one opens the app on the Payload tab and starts that chain. In a chain's editor, **Add to home screen** asks the launcher to put a chain on the home screen (if the launcher can't do that, Bento says so). Renaming a chain updates its shortcuts. A deleted chain stops working from the home screen.
- **Run when ready.** Under the chains, **Run when the PS5 is ready** (or PS4) picks one chain per console that Bento sends by itself when that console's loader starts answering after it didn't, which is what happens right after a jailbreak. It waits 3 seconds for the jailbreak to settle, runs at most once every 10 minutes, and only while Bento is open (the console is only checked then).
- Chains are part of the [backup](#about-updates-and-backup).

### Upload tab

Uploads files from your phone to the console's FTP server.

1. Check **FTP port**, **Username**, **Password** and **Remote folder** (default `/data`).
2. Tap **Choose files** (you can pick several), then **Upload in background**.

Every file becomes a queue item and is stored as `<remote folder>/<file name>`. A new FTP connection is made for each try, in passive mode with binary transfers. When the console says it has the file, Bento asks it for the stored size and compares that with what was sent, because a console can report success for a connection that broke or a disk that filled up. Files are read through Android's document picker, so Bento needs no storage permission; read access is kept, as far as the system allows, so files waiting in the queue can still be opened later.

The FTP server is the FTP payload (port 2121 by default), etaHEN's built-in one on a PS5 (port 1337), or GoldHEN's on a PS4 (port 2121). Set the port to match. How the queue handles dropped connections is under [Transfers](#transfers).

#### Keep a folder in sync

For a folder you keep adding to (your packages, your homebrew). **Add a folder…** picks a folder on the phone and asks where it goes on the console (the remote folder plus the folder's name, to start with). **Sync** reads the phone folder all the way down and the console folder too, then sends only the files the console doesn't have, or has with a different size; it says first how many are new, changed and already there, and how much that is. Missing folders are made on the console first. Nothing is ever deleted on either side. Up to 6 folders are remembered for each console; ✕ forgets one. Up to 20,000 files are compared.

### Files tab

A file browser for the console over FTP. It opens the folder you were last in on this console, or the **Remote folder** from the FTP settings the first time (and when the folder you were last in can't be opened any more).

- **Rows.** Each row shows the name and, under it, the size and the day it last changed (when the console's listing has them). Tap a folder to open it. Tap a file, or the ⋮ button on any row, for its actions: **Download to phone**, **Rename** and **Delete**. A symbolic link is tried as a folder first.
- **Buttons.** **⬆ Up**, **Refresh**, **New folder**, **Sort**, **Search**, **Upload here** (pick files to send into the folder you are viewing), **Pin this folder** and **FTP settings**. The system Back button also goes up one folder.
- **Select several things.** Press and hold a row to start selecting, then tap rows to tick or untick them. The bar shows how many are selected and has **Cancel**, **Select all** (or **Select none**), **Download to phone** and **Delete**. Back leaves the selection first.
- **Names** are checked before anything is sent: not empty, not `.` or `..`, no slashes, no control characters, and not already used in that folder.
- **Delete** asks first. Deleting a folder removes everything inside it and can't be undone: Bento walks down the folder (up to 40 levels), deletes the files, and removes each folder on the way back out. Links are removed, never followed. When several things are selected, one that can't be deleted doesn't stop the others; the message says how many were deleted, how many were not, and what the first problem was.
- **Download to phone** is a queued background transfer into `Downloads/PS5` or `Downloads/PS4` on the phone. The file stays hidden from other apps until it is complete, and a download that is cancelled or fails for good leaves nothing behind.
- **Download folders.** Select folders (or a mix of folders and files) and tap **Download to phone**, or choose **Download to phone** in a folder's ⋮ menu. Bento first looks through the folders, then asks you to confirm, showing how many files there are and their total size. Every file is saved in `Downloads/PS5` or `Downloads/PS4` in the same folders as on the console, as its own item in the transfer queue. Bento follows folders up to 12 levels deep and takes at most 2,000 files from at most 1,000 folders; if there is more than that, the confirmation says so. Links inside folders are not followed (a link can lead anywhere, even back to the folder it is in); the confirmation says how many were skipped.
- **Sort** by name, by size (largest first) or by date (newest first). Folders always come first, and the choice is remembered.
- **Search** filters the folder you are viewing as you type: every word you type has to be somewhere in the name, in any order and whatever the case.
- **Pinned folders.** **Pin this folder** adds a 📌 button for it under the toolbar (up to 12 per console); tap it to jump straight there. **Unpin this folder** removes it. If a pinned folder can't be opened (it was renamed or deleted), the app says so and offers to unpin it.
- **Pull down to refresh.** Pulling the list down when it is at its top loads the folder again, the same as **Refresh**.
- File commands use a short FTP session each, with 30-second timeouts. The listing reloads after every change and after a transfer ends.
- **📷 Screenshots & videos** opens `/user/av_contents`, where the console keeps what the Create (or Share) button captured. Tick what you want and download it. **Copy new to phone** queues every screenshot and clip not copied before into `Downloads/PS5/Captures` (or PS4), with a folder per game; Bento remembers what it copied for each console, so nothing comes over twice. With **Copy new screenshots and clips each time Bento opens** on, it does that by itself once per start when the console's FTP answers (the very first time it only notes what is already there, so a whole library isn't copied unasked).
- **Edit** (in a file's ⋮ menu) opens a small text file (`.txt`, `.ini`, `.json`, `.cfg`, `.xml`, `.log`, `.lua`, `.sh` and similar, up to 256 KB) in an editor; **Save** writes it back over FTP and keeps the old file next to it as `.bak`. Files that look binary aren't opened.
- **Download as zip** (in a folder's ⋮ menu) downloads the folder into one zip in `Downloads/PS5` (or PS4), keeping its folders inside the zip, with progress and **Cancel** (a zip that wasn't finished is removed). It uses the same limits as folder downloads and doesn't go through the transfer queue.
- **etaHEN settings** (PS5) reads etaHEN's settings file, `/data/etaHEN/config.ini`, and shows the settings its README describes as switches in four groups (services, toolbox and start-up, rest mode, game overlay), with **Open after etaHEN starts** as choices and anything else in the file as plain values. **Save** writes only the lines you changed (comments and order stay), keeps the old file as `config.ini.bak`, and most changes apply the next time etaHEN starts. The FTP server must be running to edit it.

The chips at the top switch between **Folders** (the browser above), **Games & apps** and **Saves**:

- **Games & apps.** Everything in `/user/appmeta`: Bento reads each title's `param.json` (PS5) or `param.sfo` (PS4) and `icon0.png` over one FTP connection, and shows the name (in the phone's language when the game has it), title ID and version, and whether an update is installed (`/user/patch`). Icons are kept on the phone, so the list is quicker the next time. Tap a game to open its folder (`/user/app/<title ID>`), see its saves or copy its title ID.
  - **Count sizes** adds up how much each game takes: a PS4 title's game and patch packages (one listing each), and a PS5 title's folder, walked file by file (up to 40,000 files per game; beyond that the total says "at least"). **Biggest first** sorts by size. There is no FTP command for the console's free space, so Bento shows what the games use, not what is left.
  - **Check for updates** looks up each game's newest patch on the community patch lists, [Orbis Patches](https://orbispatches.com/) for PS4 titles and [Prospero Patches](https://prosperopatches.com/) for PS5 titles (the same sources the PatchDL homebrew uses), two titles at a time, and compares it with the installed version (for a PS4 title, the installed patch's version from `/user/patch/<id>/sce_sys/param.sfo` counts). A row then says **update v1.05** or **up to date**; the game's window shows the newest patch and the firmware it needs, the newest one the console's firmware can take when that is older (a PS5 title on a PS5, a PS4 title on a PS4), and a link to its patch history. Only the title ID is sent to those sites. Sony's servers are never contacted, from the phone or the console, and nothing is downloaded or installed.
- **Saves.** The saves of each user in `/user/home/<user>/savedata_prospero` (PS5) and `savedata` (PS4), with the game's name and icon from the games list. **Back up to this phone** downloads a game's save folder into `Downloads/PS5/Saves/<title ID> <date time>`. **Put a backup back…** picks a folder on the phone and sends its files into the game's save folder (replacing files with the same name), after asking. Saves are locked to the console and the account that made them, so a backup only works on the same console for the same user. Close the game before putting a save back.

### Web tab

A built-in browser (Android's WebView) that can send downloads to the console. It starts on `https://www.superpsx.com/category/ps5/ps5-games/`, a third-party website that Bento is not affiliated with. Change the start page with **More > Home page**, or make any bookmark your homepage. On the Web tab the console card is left out, so the page gets the room.

- **Buttons.** One row: **Home**, **Reload**, **Tabs** and **More**. **More** holds **★ Add** (bookmark the page), **Bookmarks** (open, make home, or remove; up to 100), **Find**, **Home page**, **Ad block** and the **Desktop site** switch.
- **Tabs.** **Tabs: N** opens the list of open tabs: tap a tab to show it, tap ✕ to close it, and **New tab** opens one with your homepage. Up to 8 tabs can be open at once; asking for a ninth shows a message. Each tab has its own page and its own history, a new tab opens right after the one you are on, and the pages of tabs you are not looking at are paused where Android allows. Closing the last tab leaves a new one with your homepage. The open tabs come back when the screen is rebuilt (rotating the phone, changing dark mode), but they are not saved when the app is closed.
- **Press and hold a link** for a menu: **Open**, **Open in new tab**, **Copy link** and **Send or download…**, which offers the same four choices as a download (see below). On a picture that is a link, the menu is for the link, not for the picture. Things that aren't web links (an e-mail address, a `javascript:` link) get no menu. A link you open from this menu is not asked about again by "Leave this site?".
- **Find in page.** **More > Find** opens a bar under the buttons. Type to search the page; the counter shows the match you are on (such as 3/12) or **No matches**; ▲ and ▼ go to the earlier or the next match; ✕ closes the bar. A page that loads while the bar is open is searched again for the same words.
- **Desktop site.** The switch asks websites for their computer pages instead of the phone pages. It applies to all tabs and is remembered; the page you are looking at reloads at once, and the other tabs reload once, when you next show them.
- **Pull down to reload.** Pulling down a page that is at its top loads it again.
- **The Back button** does the first of these that applies: close the find bar; go back one page in the tab; close the tab (when more than one is open); otherwise leave the app, as usual.
- **Links work like in a normal browser.** Web links open in the same view, links that ask for a new window open there too when you tap them, popups that nobody tapped are ignored, and links that aren't web links (such as `market:`) go to the system. Bento also removes the "wv" marker from the browser's user agent, because some sites treat WebView as a bot and break their links.
- **Leave this site?** If a tap would take the page to a different site than the one you are on, which is what ad redirects do, Bento asks first. **Open** allows that site for this session, **Block** stays put. A "site" is the registered domain; endings such as `.co.uk` are understood.
- **Ad block.** A list of 57 well-known ad and pop-up network domains, plus your own (one per line). Requests for parts of a page (scripts, images, frames) from those domains or their subdomains get an empty answer, and a counter shows how many were blocked. The page you are opening is never blocked. Reload after changing the list. It is a domain list, not a full content blocker.
- **Downloads** open a chooser with four choices:
  - **Send link to the console installer.** The same request as the Install tab, with the download's address. The installer needs a direct link it can open without logging in.
  - **Upload to the console (FTP).** The phone downloads the file and streams it to the console's FTP server without keeping a copy. The browser's cookies and user agent go with the request, so links that need your login session work. If the connection drops, Bento continues with a `Range` request (with `If-Range`, so a file that changed isn't mixed with the old one).
  - **Save to payload shelf.** For payloads up to 64 MiB.
  - **Save to phone.** Android's download manager puts it in `Downloads`.

### Memory tab

A cheat engine for games on a PS5 or a PS4. It talks to [ps5debug-NG](https://github.com/Pharaoh2k/ps5debug-NG), a debugger payload that runs inside the PS5's system shell (firmware 3.00 to 13.60, 10.40 included), or on a PS4 to [ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG) (firmware 5.05 to 13.52), which speaks the same protocol. Both listen on port `744`. Older debuggers (Ctn's ps4debug 1.1, the first ps5debug) work too: they don't answer the name request, so Bento asks again without it and leaves out what they lack (the foreground game, fast unknown-value scans). The console does the searching; only the matches come over Wi-Fi. Send `ps5debug-NG.elf` from the Payload tab, then tap **Connect**: the tab shows the game in the foreground (name, title ID, version, pid) and the console shows "Bento connected". Three parts sit under the header:

**Scan**

- **Value type:** 1, 2, 4 or 8 bytes, Float or Double (4 bytes is the usual one).
- **First scan:** **Exact value**, **Bigger than**, **Smaller than**, **Between**, or **Unknown value** (for bars and hidden numbers: the console remembers every non-zero value of that type; needs ps5debug-NG 1.3 or newer).
- **Next scan:** Exact value, Bigger, Smaller, Between, Increased, Increased by, Decreased, Decreased by, Changed, Unchanged.
- **Skip slow memory** (on by default, ps5debug-NG 1.3+): leaves out regions of 64 MB or more that are uncached or read slower than 100 MB/s (graphics memory). Turn it off if a value is not found.
- **Matches:** the first 200, with the value now and at the scan before; with 200 or fewer they update every 1.5 seconds. A search that started with an unknown value stays on the console while it has more than 3 million survivors, then comes over to the phone. A first scan that finds more than 3 million matches stops and asks for a more unusual value. **Cancel** stops any scan at once.
- **Tap a match** to write a value, see its 8 bytes read as every type, **Add to cheats**, **Watch** it (it stays at the top of Scan with its live value) or **Show in memory**.
- **Text** (the last value type) finds words: a character's name, an item, a line of dialogue. Type it exactly as the game shows it (capitals count, at least 3 bytes); the console checks every byte offset, in UTF-8 and UTF-16 (either can be turned off), about as long as a first scan. Each match shows the text around it. Tap one to open it in Browse, copy the address, or write new text over it: new text can't be longer than the old (a shorter one is ended with zeros), and the write can be undone. Needs ps5debug-NG or ps4debug-NG (the byte-pattern search with a mask).

**Undo**

- Before every write (a value, bytes in Browse, a cheat-file mod switched, text) Bento reads the bytes that are there, and when a cheat is frozen it keeps the value it had before. Under the header, **Undo** puts back the newest change and **History** lists the last 50 for the running game, newest first, with **Restore everything** to put all of them back. Undoing a freeze also stops it; undoing a cheat-file mod also sets its switch back. Undo writes the old bytes exactly, so if the game changed the value in between, the old value replaces the new one. The history is cleared when the game closes.

**Cheats**

- **Cheat files.** **Find cheats for this game** reads the list of the community cheat files etaHEN uses ([etaHEN/PS5_Cheats](https://github.com/etaHEN/PS5_Cheats)) and opens the one for the game's title ID and version, or lists the versions there are. Each cheat has a switch (or **Apply**, for one-time cheats). Offsets are counted from where the game's `eboot.bin` is loaded. Before it writes, Bento reads the code there and only changes it when it is the file's "on" or "off" bytes, so a file for another version can't break the game. Cheats that use a code section are shown but not offered yet. The **Cheat source** can be changed (the fake console serves its own).
- **My cheats.** Saved per game by title ID: a name, an optional group, the address, the kind of value and the value. Tap one to edit it, **Write now**, **Watch**, **Find again** or remove it.
- **Freeze** writes the value again five times a second (several at once with ps5debug-NG capability 1.0+). It keeps going while Bento is in the background, with a notification and a **Stop** button, and when the console goes away (rest mode, Wi-Fi) it retries for three minutes and carries on once it is back.
- **Find all again.** When a cheat is saved from a match, Bento keeps the 24 bytes on each side of it, leaving out the value and anything that looks like an address. After the game restarts, this looks for those bytes (they must occur exactly once) and moves each cheat to where they are now.
- **Share** sends the game's cheats as text; **Import** adds a shared list (cheats already there are skipped).
- **Find pointer** (in a cheat's window). Most values sit somewhere new every time the game starts. A pointer path starts at a fixed place in the game's own file and follows pointers to the value, like `[eboot+1A2B0] +18 → +40`. Bento searches backwards from the cheat's address: places holding a pointer at most 0x1000 below it, places pointing just below those, and so on, up to three pointers, until it reaches the game's eboot (its code, data and the unnamed data right after it). Each step is a search on the console for 8-byte values in a window, so it can take a few minutes. Many paths only lead there by chance: restart the game, find the value again, type its new address and tap **Keep**, and only the paths that still lead to it stay. **Use it** gives the cheat that path; from then on it moves to where its path leads each time you connect and on **Find all again** (🔗 marks such cheats).
- **Make cheat file** turns your cheats into a cheat file in the PS5_Cheats layout (offsets from the eboot's load address, your value as "on" and the bytes there now as "off"), named like `PPSA01234_01.000.000.json`. Only cheats inside the eboot can go in; values on the heap can't, and the window says how many were left out. **Share**, **Save** (to `Downloads/PS5/Cheats`) or **Copy** it.

**Console log**

- Shows the console's kernel log as it is written: ps5debug-NG serves it on port `3232` (the port can be changed). **Start**, **Stop**, **Pause** (the screen holds still while lines keep coming in), **Follow** (keeps the newest line in sight), a filter that shows only lines with the text you type, **Copy** and **Clear**. The last 4,000 lines are kept. This view works without connecting the debugger first.

**Learn**

- A **Learn** chip, there even before connecting, teaches finding values in three levels sorted by how hard the value is to find. **Easy:** numbers the game shows (gold, ammo; exact 4-byte values, freeze, undo, too many matches). **Medium:** values without a number or that keep changing (a health bar as a Float with Unknown value and Decreased/Unchanged, a countdown timer, values next to each other, what to try when Exact finds nothing). **Expert:** values that move each restart (pointer paths, Find all again), changing the game's code (a NOP over the damage routine), and text. Each level has a "what to look for" card, and each lesson its own explanation and steps.
- **Guide me** turns a lesson into a coach bar at the top of the Memory tab: it shows the current step and moves on by itself when it sees the step done (a search of the right kind, few matches left, a write, a cheat added or frozen, an undo, a pointer path in use), with **Back**, **Next** or **Skip**, and **Stop**.
- **The practice game** (**Use the practice game**) is a tiny pretend game inside Bento with a pretend debugger in front of it on this phone (127.0.0.1). The debugger speaks ps5debug-NG's protocol for every command Bento uses (scans, the unlock, the unknown-value snapshot, byte patterns, writes), so the whole Memory tab works on it without a console. It keeps gold and ammo as 4-byte numbers (with a few other places holding the same numbers, as in a real game), health as a Float bar with max health beside it, a countdown in Float seconds, the hero's name in UTF-8 and UTF-16, a damage routine in its code, and a player record reached through pointers from its own data; **Restart game** moves its memory, as a real game does. **Back to my console** connects to the console again.

**More while searching**

- **Back one scan** steps a search back to the results before the last Next scan (up to three steps, for searches kept on the phone).
- **Neighbors** (in a match's window) reads the bytes around it as the same kind of value, with their distance; **Use** puts one into the new-value field (to fill health up to the max beside it).
- **Quick values**: buttons for 0, 1, 99, 999 and 9999 (0, 1, 99, 255 for a byte) and the last values you wrote, one tap each, wherever a value is written.
- **Cheat files for PS4 games** are looked up in GoldHEN's own cheat repository as well as etaHEN's (while the usual source is set); each file in the list says which one it comes from.

**Browse**

- Every process, the memory regions of one (start, size, `rwx`; writable ones are tinted), and a hex view of 256 bytes with **Previous**, **Refresh**, **Next** and **Go to address**. **Tap a line** to change its bytes. **Add as cheat** saves the address on screen.

Everything stays while you switch tabs. **Offline games only:** changing memory in an online game can get the account banned, and it spoils the game for others.

### Share sheet

Share a link from any app (a browser, a chat, a note) and choose **Bento**. The first `http://` or `https://` address in the shared text opens the same chooser as a Web-tab download. If Bento is already open, the link goes to the running app.

### Transfers

FTP transfers share one background queue: uploads from the Upload and Files tabs, web links relayed over FTP, and downloads from the console. Install requests, payloads, payload chains and "Install from this phone" are separate and not part of it.

- **One at a time.** Items run in order in a foreground service, so they carry on when you leave the app or the screen turns off. While it runs, the service holds a CPU wake lock and a Wi-Fi lock and lets go of them when the queue is done (the wake lock also has a 12-hour safety limit). Without them Android may slow down or pause a long transfer after the screen goes off.
- **The transfer card** is pinned above the bottom bar on every tab: name, percentage, speed (averaged over the last 8 seconds), time left, **Pause** and **Cancel**, the **Speed limit** button, and a "waiting" button that opens the queue, where you can remove one item or **Clear all**. A transfer that worked hides itself after 15 seconds, and a short buzz marks the end when you are looking at the app. The notification shows the same, with **Pause** (or **Resume**) and **Cancel** (or **Cancel all** when others are waiting). When a run ends while the app is hidden, the result arrives as a separate notification on its own channel ("Transfer results"), which you can silence in Android's settings.
- **Pause and resume.** The pause button on the card, or in the notification, holds the running transfer: Bento lets go of the connection and of the phone's wake and Wi-Fi locks while it waits. **Resume** reconnects and carries on from where it stopped, the same way as after a dropped connection (see below). Cancelling a paused transfer ends it. The time spent paused doesn't count in the summary of the run.
- **Speed limit.** The **Speed limit** button on the card (the same setting is in the About window) caps transfers at 1, 2, 5, 10 or 20 MB per second, or removes the cap, so the Wi-Fi stays free for other things. It is saved, and a change applies at once, also to a transfer that is running. Bento keeps the average speed at the cap by moving the data in small pieces and waiting between them.
- **Summary of a run.** When the queue has worked through two or more transfers, the card and the notification say how the run went as a whole: `Transfers done: 4 of 5`, how much was moved and how long it took, and how many failed or were stopped. A single transfer keeps its own message.
- **A failure waits for you.** If a transfer fails for good, the queue pauses on it and the card offers **Try again** (it goes first) and **Skip**, or only **Dismiss** when trying again can't help (a wrong login, say). Cancelling a running transfer from the card moves on to the next one.
- **Unfinished transfers are offered again.** What is queued, what is running, and what failed and is waiting for you is also kept in a small file in the app's private storage. If the app is closed or killed before the queue is empty, the next start shows a card, **Unfinished from last time: N**, with the first names and two buttons. **Continue** puts the transfers back in the queue, each one brought up to date: it uses the console's current address, the FTP password you typed in this session (it was never saved) and the browser's cookie for a link. Each transfer starts again from the beginning. A file picked from the phone that can't be found any more is left out, and the status line says how many. **Discard** forgets them. The file never holds a password or a cookie.

What happens when the connection drops:

1. Bento notices an error, or no data for 60 seconds (a watchdog closes the sockets).
2. If no data had moved yet, it reports the problem straight away. A wrong address, a wrong login or a dead link shouldn't make you wait.
3. Otherwise it waits 3, 6, 12 and 24 seconds, then 30 seconds each time, and says so on the card ("Connection lost. Trying again in N s…").
4. It reconnects and asks the console how much of the file it has (`SIZE`, re-checked up to 5 times, a second apart, until the number stops changing), then continues from there with `REST`. Only the missing part is sent. Web links continue with `Range`; files from the phone are read from the middle.
5. At the end it compares the stored size with the expected one.
6. If the console can't continue a file (an error 500 to 504 in reply to `REST`) or the sizes don't add up, Bento starts that file over from the beginning and stops trying to continue it.
7. It gives up when it has retried 10 times in a row without getting anywhere. A try counts as progress if it got further into the file than any try before, or started further on.

### Errors that say what to do

Many problem messages (on the status line, the transfer card, the Files tab and the About window) have a **What can I do?** button. It opens a short page with three parts: **What happened**, **What to try**, and **Details**, which is the message as it was shown, with a **Copy details** button. Nine kinds of problem have a page:

| Page | When it appears |
| --- | --- |
| Can't reach the console | Nothing answers at the console's address (unknown host, no route, a connection that never opens). It has a **Find my console** button. |
| That service isn't running | The console answered, but the port refuses connections. |
| FTP login refused | The FTP server turned the user name or password down. |
| The connection dropped | The link broke or stopped moving data. |
| The installer didn't take the link | The installer is there but refused the link. |
| The console stopped storing | The console kept less than it was sent: a full or read-only drive. |
| That folder or file was refused | The FTP server won't open, write or change it. |
| The phone can't use the file | The file is gone, the app lost access to it, or the phone is out of space. |
| The download link failed | The website behind a link answered with an error (HTTP 400 or higher). |

A message that doesn't fit one of these has no button, so the button never promises more than the app knows. The pages only explain; they don't send anything.

### About, updates and backup

Tap the app name at the top (or **About & backup** in the console switcher) to open the About window.

- **Updates.** The window shows the installed version. Bento can check the Releases page for a newer build: it asks GitHub for the newest release of this repository, and when that release's number is higher than the installed one, a notice **Version 1.N is available** appears at the top of the screen. **Download** opens the APK in your browser (you install it by hand), and **Later** hides the notice for that version. **Check now** checks right away. **Check for updates when the app opens** is on by default and means at most one check a day; you can switch it off in the same window. If GitHub's API refuses (a shared connection can use up its small allowance), Bento falls back to the website's "latest release" page. A check that fails isn't counted as done, so the next start tries again. Nothing about you or your consoles is sent; GitHub sees your IP address, as any website does.
- **Speed limit.** The same setting as the **Speed limit** button on the transfer card.
- **Look.** **Pure black in dark mode** (for OLED screens) and **Text size** (Normal, Large, Larger, on top of the phone's own size).
- **Backup.** **Save backup** writes one file (`bento-backup-<date>.json`) to a place you choose. It holds your consoles (without their FTP passwords), the homepage, the bookmarks, the ad-block switch and list, the payloads on the shelf (up to 16 MB together; any left out are counted), your payload chains and your pinned folders. **Restore backup** reads such a file and shows what is in it and when it was made, then asks before it changes anything. Restoring replaces your consoles, homepage, bookmarks and ad-block settings with the ones in the file, adds its payloads to the shelf (a payload with the same name is replaced), merges its chains with yours (a chain with the same id is replaced), and sets the pinned folders of the consoles the file has some for. A part that the file doesn't have is left as it is. A file that isn't a Bento backup, or that comes from a newer version of Bento, is refused with a message. The screen starts again afterwards to show the restored settings, and you type the FTP passwords again. With **Make a backup by itself once a week** on, Bento saves the same file into `Downloads/Bento/Backups` when it opens and the last one is a week old (each time a new file, so older ones stay).
- **Copy debug info** and **Share debug info.** A report for a bug report or a forum post: the app version, the phone model, Android version and language, the app's language, how many consoles are set up, the selected console's type and firmware, the debugger's name, protocol and firmware (when the Memory tab is connected), the payloads you got from the store and their versions, a short summary of the last connection check, the last message the app showed and the last five problem messages, each cut to 600 characters, with web links replaced by `(link)` and IP addresses by `x.x.x.x`. The text appears in the window too, so you can see what you would share. The labels are in English; messages are as the app showed them.
- **Releases page** opens the Releases page in the browser.

### Languages and look

- **53 languages:** English plus 52 translations (list below), chosen with the **Language** button in the console switcher: every language is listed in its own name, plus **Phone's language**. On Android 13 and newer this is the same per-app language as in the phone's settings; on older versions Bento keeps the choice and applies it itself.
- **Right-to-left** languages (Arabic, Persian, Hebrew, Urdu) mirror the layout. Values such as paths and addresses are wrapped in Unicode isolates so they keep their own order inside a right-to-left sentence.
- **Dark and light** themes follow the phone, with one accent color per console.
- **The icon** is an adaptive icon with a themed (monochrome) version for Android 13 and newer.
- The translations have not been reviewed by native speakers yet.

<details>
<summary>All 53 languages</summary>

Afrikaans, Arabic, Bengali, Bulgarian, Catalan, Chinese (Simplified), Chinese (Traditional), Croatian, Czech, Danish, Dutch, English, Estonian, Filipino, Finnish, French, German, Greek, Gujarati, Hebrew, Hindi, Hungarian, Indonesian, Italian, Japanese, Kannada, Korean, Latvian, Lithuanian, Malay, Malayalam, Marathi, Norwegian Bokmål, Persian, Polish, Portuguese (Brazil), Portuguese (Portugal), Punjabi, Romanian, Russian, Serbian, Slovak, Slovenian, Spanish, Swahili, Swedish, Tamil, Telugu, Thai, Turkish, Ukrainian, Urdu, Vietnamese.

</details>

## Under the hood

Bento is Kotlin with Jetpack Compose and Material 3: 43 source files, about 12,300 lines, in one module (`app`).

### Design notes

- **Shared state.** The screen and the services share a few objects: `TransferState` and `ServeState` for transfers and sharing, `ChainRunner` for payload chains. The services update them, and the screen watches their `StateFlow`s.
- **Services do the long work.** Transfers, sharing and payload chains run in foreground services (type `dataSync`), so they survive leaving the app.
- **No storage permission.** Files are picked with Android's document picker and kept readable with persistable URI permissions. Downloads to the phone go through MediaStore into `Downloads/PS5` or `Downloads/PS4`.
- **The engine is separate from the app.** `Engine.kt` doesn't touch the screen or the phone's storage; the app plugs those in through small interfaces (`TransferHooks`, `ResumableSource`, `ResumableSink`). The files `Engine.kt`, `Queue.kt`, `Speed.kt`, `Tally.kt`, `TransferState.kt`, `Unfinished.kt`, `Scan.kt`, `Status.kt`, `HttpSource.kt`, `FileServer.kt`, `ServeSupport.kt`, `ServeState.kt`, `BrowserLogic.kt` and `Basics.kt` don't import any `android.*` classes at all.
- **Decisions first, screens second.** What can be decided without a phone is kept in plain Kotlin so it can be tested on a computer: how a folder is sorted and searched and what a download of folders comes to (`FilesLogic.kt`), what a chain does (`Chains.kt`), which addresses the Wi-Fi scan tries (`Scan.kt`), how links are picked out of text (`Links.kt`), how a backup is read (`Backup.kt`), how the speed limit paces a transfer (`SpeedLimit.kt`), which tabs are open and what they are called (`BrowserLogic.kt`).
- **The browser talks to pages through three small interfaces.** `BrowserModel` in `Browser.kt` holds the tabs and the find bar, and works with a page (`TabPage`), the news a page sends (`PageEvents`) and the frame that shows it (`Stage`). `BrowserWeb.kt` is the one place that turns those into real web views, which keeps the rules of the browser testable without Android.
- **What must survive is kept where it can.** The list of unfinished transfers is a small file in the app's no-backup storage. The open browser tabs are kept as text in the state Android holds while the screen is rebuilt, so they survive a rotation but not closing the app.

### Checking the build

GitHub builds every push (`.github/workflows/main.yml`) and puts the APK on the Releases page. A second workflow, `.github/workflows/tests.yml` ("Phone test"), runs the app on a virtual Android 14 phone: it installs the two newest releases one over the other (so a wrong signing key shows up at once), opens every tab, turns the screen, leaves the app and comes back, shares a link into it and switches through all 53 languages. Each time it checks that the app is still running, has not crashed or stopped responding, and shows its six tabs (in the right order, and not cut off, in right-to-left languages too). The results are on the run's page under Actions: a table of every check, an annotation for each failure, and the screenshots and the phone's log under Artifacts. The script is `.github/scripts/ui_smoke.sh`.

### Source map

Paths are relative to [`app/src/main/java/com/example/pssender/`](app/src/main/java/com/example/pssender/).

#### Screens

- `MainActivity.kt`: the activity and the root screen: tabs, console checks, update check, share-sheet intake, chain requests from the app icon, the download chooser, the "Leave this site?" window, and the Install and Upload tabs.
- `Chrome.kt`: the frame around every screen: header, console card, bottom bar, transfer card, queue dialog, speed limit window, status line.
- `Files.kt`: the Files tab and the FTP settings fields.
- `Web.kt`: the Web tab: web view setup, the check that notices a tap leaving the site, ad block, bookmarks, the toolbar.
- `Browser.kt`: the tabs of the browser (which are open, which is shown, find in page, desktop mode, the Back rule) and the windows for the tab list, the link menu and the find bar.
- `BrowserWeb.kt`: the web view of each tab, inside its pull-to-refresh layout, and the frame that shows the shown one.
- `Shelf.kt`: payload shelf storage and the Payload tab.
- `ChainUi.kt`: the list of chains, the chain editor and the card of a running chain.
- `Serve.kt`: the "Install from this phone" section.
- `Profiles.kt`: consoles, how they are saved, and the switcher dialog.
- `Find.kt`: the "Find my console" window.
- `About.kt`: the About window: updates, speed limit, backup and restore, the debug info.
- `Diagnose.kt`: **What's wrong?**: the connection measurements, the findings and fixes, and its window.
- `Language.kt`: the in-app language picker and how the choice is applied on older Android versions.
- `UnfinishedUi.kt`: the card that offers the unfinished transfers again.
- `Help.kt`: the "What can I do?" pages and the notes that say which page a message gets.
- `Theme.kt`: dark and light colors, and the six accents.
- `Icons.kt`: the few icons, drawn in code so no icon library is needed.
- `Memory.kt`: the Memory tab's screens: Scan, Cheats and Browse, and their dialogs (pointer paths, cheat file).
- `Pointers.kt`: pointer paths: the backwards search from an address to the eboot, and following a path.
- `Klog.kt`: the console log view (port 3232).
- `ConsoleMeta.kt`: reading `param.sfo`, `param.json` and the header of a PS4 package.
- `ConsoleTools.kt`: the Files tab's Games & apps and Saves, the package check, and folder sync.
- `Store.kt`: Get payloads: the payload lists, GitHub releases, zips, checksums, firmware notes, notices of new payloads and updates.
- `StoreIndex.kt`: where payloads come from: Bento's online list, the community list, the GitHub search, and which ones are new.
- `Firmware.kt`: each console's firmware, and reading which firmware a payload's page names.
- `PkgCheck.kt`: a checked package against what the console has installed.
- `MemorySession.kt`: what the Memory tab keeps while the app runs: the connection, the search, text search, the cheats (saved per game), the freeze loop and the undo history.
- `MemoryText.kt`: text search: encodings, overlapping pieces, previews and replacement text.
- `MemoryLearn.kt`: Memory > Learn: the lessons, the coach bar and the practice game's card.
- `PracticeConsole.kt`: the practice game and the pretend ps5debug-NG in front of it on 127.0.0.1.
- `GameUpdates.kt`: game updates from the community patch sites.
- `ConsoleExtras.kt`: game sizes, copying new captures, and the shelf's verified checksums.
- `ConsoleEdit.kt`: etaHEN's settings and the text editor for small files on the console.
- `CheatFiles.kt`: reading the community cheat files (and a small JSON reader).
- `FreezeService.kt`: keeps frozen values frozen while the app is in the background.
- `Tips.kt`: the ? buttons, their how-tos, and the folding Settings rows.
- `Welcome.kt`: the welcome page.
- `Intro.kt`: the logo opening.
- `Texts.kt`: the app class, text lookup for code that has no screen, right-to-left isolation, language settings.

#### Console and network

- `Net.kt`: install requests, payload sending, FTP browsing and file operations, and the glue between transfers and the phone's storage.
- `Status.kt`: the console check (which ports answer) and the card's states.
- `Scan.kt`: the Wi-Fi scan behind "Find my console".
- `HttpSource.kt`: a web link as a source that can continue after a break.
- `Links.kt`: finding web links in text, sending several one after another, the recent links.
- `Update.kt`: the update check, the notice, and the update part of the About window.
- `Ps5Debug.kt`: the ps5debug-NG wire protocol (port 744): process list, current game, memory maps, read, write, the scan unlock, first and next scans, bulk reads; and the hex view lines.
- `Scanner.kt`: value types and scan types, and the search itself (first scan, next scan, live values).

#### Transfer engine

- `Engine.kt`: FTP upload and download with retry, resume, stall detection, pause and cancel.
- `Queue.kt`: the queue, including the failed item that waits for you.
- `Speed.kt`: speed and time-left estimate, and their formatting.
- `SpeedLimit.kt`: keeping a transfer under a speed limit, and the saved setting.
- `Tally.kt`: counting a run of transfers for its summary.
- `Unfinished.kt`: the saved list of unfinished transfers and how it is brought up to date.
- `TransferState.kt`: what the transfer card shows, and the queue with pause, cancel and the unfinished list.
- `TransferService.kt`: the foreground service that runs the queue, with the notification and the locks.

#### Payload chains

- `Chains.kt`: chains, how they are saved, and the loop that sends one.
- `ChainService.kt`: the foreground service that runs a chain.
- `ChainShortcuts.kt`: the shortcuts on the app icon and the home screen.

#### The Files and Web logic

- `FilesLogic.kt`: sort, search, ticking, the plan for downloading folders, deleting several things, pinned folders.
- `BrowserLogic.kt`: the rules for the browser tabs, tab names, the address behind a pressed link, what the browser tells websites about itself, the find counter, and saving the tabs as text.

#### Sharing from the phone

- `FileServer.kt`: the HTTP file server.
- `ServeSupport.kt`: link names, tokens, choosing the phone's address, opening a file at a position.
- `ServeState.kt`: one sharing session: links, progress, speed.
- `ServeService.kt`: the foreground service for sharing.

#### Helpers

- `Basics.kt`: size formatting, error text, the FTP settings class.
- `Backup.kt`: what a backup holds, writing and reading the file, putting it back.
- `Device.kt`: small things asked from the phone: the clipboard, the app's version, opening a link, the phone summary.

Beside the code: `AndroidManifest.xml` (permissions, services, the share-sheet entry), `res/values*/strings.xml` (every text, in 53 languages), `res/xml/locales_config.xml` (the language list), the icon drawables, `build.gradle.kts` and the signing key.

<details>
<summary>Key functions</summary>

- `installRequest` (`Net.kt`): Sends the two install request styles and decides whether the console accepted.
- `sendInstallBatch`, `linksIn` (`Links.kt`): Send a list of links one after another, and pick the links out of pasted text.
- `sendBytesToLoader` (`Net.kt`): Streams a payload over TCP, then closes the sending side.
- `runChain` (`Chains.kt`): Sends the payloads of a chain with their pauses, and tries a loader that isn't ready again.
- `isPortOpen`, `checkConsole` (`Net.kt`, `Status.kt`): The port checks behind the console card.
- `scanNetworks`, `scanWifi`, `sortHits` (`Scan.kt`): Which addresses "Find my console" tries, trying them, and putting the likeliest console first.
- `ftpList`, `ftpRename`, `ftpMakeDir`, `ftpDelete` (`Net.kt`): Short FTP sessions for the Files tab.
- `arrangeEntries`, `planDownload`, `deleteEach` (`FilesLogic.kt`): Sorting and searching a folder, planning a download of folders, deleting several things.
- `ftpUpload`, `ftpDownload` (`Engine.kt`): Upload and download that resume after a drop.
- `ftpDownloadToPhone` (`Net.kt`): Downloads into `Downloads/PS5` or `Downloads/PS4` through MediaStore.
- `runWithRetries` (`Engine.kt`): The retry loop: waits, resumes, gives up.
- `runAcrossPauses` (`TransferState.kt`): Runs a transfer across the user's pauses.
- `settledSizeOf` (`Engine.kt`): Waits until the console's partial file stops growing.
- `StallWatchdog` (`Engine.kt`): Closes the connections after 60 seconds without data.
- `Pacer`, `pieceFor` (`SpeedLimit.kt`): Keep a transfer at or under the speed limit.
- `RunTally` (`Tally.kt`): Counts a run of transfers for its summary.
- `UnfinishedCodec`, `FileJournal`, `refreshSaved` (`Unfinished.kt`): The saved list of unfinished transfers, writing it in the background, and updating an entry before it runs again.
- `HttpSource` (`HttpSource.kt`): Reads a web link and continues with `Range` and `If-Range`.
- `TransferQueue` (`Queue.kt`): One worker, waiting items, and the failed item that waits for you.
- `SpeedMeter` (`Speed.kt`): Speed over the last 8 seconds, and time left.
- `TransferCenter` (`TransferState.kt`): The queue plus what the card shows; the shared instance is `TransferState`.
- `TransferService` (`TransferService.kt`): Runs the queue as a foreground service.
- `FileServer` (`FileServer.kt`): The HTTP server for "Install from this phone".
- `chooseServeAddress` (`ServeSupport.kt`): Picks the phone address the console can reach.
- `safeLinkName`, `newToken` (`ServeSupport.kt`): A clean file name and a 96-bit random token for a link.
- `ServeCenter` (`ServeState.kt`): One sharing session; the shared instance is `ServeState`.
- `shelfAdd` (`Shelf.kt`): Copies a payload onto the shelf, replacing one with the same name only when complete.
- `siteOf`, `isOffSite` (`Web.kt`): Decide whether a tap leaves the current site.
- `AdBlock`, `hostMatches`, `parseHosts` (`Web.kt`): The ad domain list and how a request is matched against it.
- `buildWebView` (`Web.kt`): Sets up the browser: links, popups, ad block, downloads, pressed links.
- `BrowserModel` (`Browser.kt`): The tabs, the find bar, desktop mode and the Back rule, without any Android type.
- `withNewTab`, `withoutTab`, `tabsToJson`, `tabsFromJson` (`BrowserLogic.kt`): The rules for the tab list and how it is saved as text.
- `desktopUserAgent`, `linkAddress`, `findCounter` (`BrowserLogic.kt`): What the browser says about itself in computer mode, which address a pressed link means, and the "3/12" counter.
- `checkForUpdate` (`Update.kt`): Looks up the newest release and compares it with the installed version.
- `backupToJson`, `parseBackup`, `applyBackup` (`Backup.kt`): Write a backup, read one safely, and put it on the phone.
- `Hints`, `topicFor` (`Help.kt`): Remember which help page a problem message gets, and what the network said.
- `isolateArgs` (`Texts.kt`): Keeps values intact inside right-to-left sentences.
- `accentAt`, `appColorScheme` (`Theme.kt`): The accent for a console and the color scheme built from it.

</details>

<details>
<summary>Defaults and limits</summary>

| What | Value |
| --- | --- |
| Installer, loader and FTP ports | 12800; loader 9021 (PS5) or 9090 (PS4); 2121 |
| FTP login and start folder | `anonymous` (no password), `/data` |
| Console check | every 6 s, 1.5 s per port |
| Find my console | up to 2 networks of at most 254 addresses; 64 attempts at a time, 0.45 s each; ports: yours plus 12800, 9021, 9090, 1337, 2121 |
| Install request | 5 s to connect, 10 s to read |
| Install box | up to 50 links per send; 10 recent links |
| Payload send | 5 s to connect, 64 KB pieces |
| Payload shelf | 64 MiB per file |
| Payload chains | up to 20 chains of up to 12 payloads; pauses 0 to 300 s (3 s when added); names up to 24 characters; a loader that isn't ready is tried again for up to 15 s; 4 chains on the app icon |
| FTP file commands | 30 s timeouts |
| FTP transfers | 10 s to connect, 60 s timeouts, 256 KB buffer |
| Stall watchdog | 60 s without data |
| Retry | up to 10 retries in a row without progress; waits 3, 6, 12, 24, then 30 s |
| Speed estimate | last 8 s; time left hidden beyond 99 hours |
| Speed limit choices | none, 1, 2, 5, 10, 20 MB/s |
| Unfinished transfers | up to 200 kept |
| Notification updates | at most once a second |
| Wake and Wi-Fi locks | held while transfers run (not while paused), while sharing from the phone and while a payload chain runs; the wake lock has a 12-hour safety limit |
| File server | port 8000 if free; 16 connections; 15 s to send a request; 60 s stall; 30 min idle |
| Link token | 24 hex characters (96 bits) |
| Files tab | pinned folders: 12 per console; a download of folders: up to 2,000 files, 1,000 folders, 12 levels; a delete walks up to 40 levels |
| Web tab | up to 8 tabs; bookmarks: up to 100 |
| Built-in ad domains | 57 |
| Update check | at most once a day on its own; 8 s timeouts |
| Connection check | 1.5 s per port, all at once; one ping of 1 s |
| Get payloads | lists read at most every 6 hours (or on request); README firmware lines kept 3 days; GitHub search: up to 20 results, at least 5 stars, pushed in the last 18 months |
| Memory undo | last 50 changes per running game |
| Text search | at least 3 bytes; up to 1,000 matches kept (all counted); 32 bytes of context each side |
| Backup | payloads up to 16 MB together |
| Console name | up to 16 characters |
| Finished transfer card | hides after 15 s (if it worked) |

</details>

## Build, signing and releases

- **Where the code is.** The Android project is in this repository as plain files: [`app/`](app/) holds the code, the texts in every language and the icon drawables, with `build.gradle.kts`, `settings.gradle.kts` and `gradle.properties` at the top. Beside it are `.github/workflows/main.yml` (the build), [`fake_console.py`](../fake_console.py) (a stand-in console for testing, see [Testing without a console](#testing-without-a-console)), the [license](../LICENSE) and this README with its icon, `bento-icon.png`. The project started as "PS5 Sender", which is why the package (`com.example.pssender`) keeps that name while the app is called Bento.
- **The workflow** (`main.yml`, named *Build APK*) runs when you start it by hand or push to `main`. It checks out the repository, sets up Temurin JDK 17 and Gradle 8.9, accepts the Android SDK licenses, runs `gradle assembleDebug`, and names the result `Bento-1.N.apk`, where N is the run number.
- **Releases.** The APK is kept as the run's `bento-debug-apk` artifact and published as a release tagged `v1.N` ("Bento 1.N"). The app's `versionCode` is N too, so every build counts as an update of the one before it and Android installs it over the old one. The update check inside the app compares that number with the newest release's tag.
- **Signing.** Every build is signed with the same key, `app/pssender-debug.keystore`. Otherwise a GitHub build machine would make a new random key on every run, and Android refuses to install an app over a copy signed with a different key. **Don't replace that file**: the next build wouldn't install over the old ones, and you would have to uninstall first and lose your saved settings. It is a public debug key, so it can't prove who built an APK. Install builds you made yourself or got from this repository's Releases.
- **Building from your own repository.** The update check looks at the Releases page of `DubDeezo-hub/Bento-Beta` (the constant `UPDATE_REPO` in `Update.kt`). If you build Bento from a repository of your own, change that line, or switch the check off in the About window.
- **Toolchain.** Android Gradle Plugin 8.5.2, Kotlin 2.0.20 with the Compose compiler plugin, Gradle 8.9, JDK 17, `compileSdk` and `targetSdk` 34, `minSdk` 29 (Android 10). Dependencies: Compose BOM 2024.09.00, `androidx.activity:activity-compose` 1.9.2, Material 3, AndroidX SwipeRefreshLayout 1.1.0 (pull down to reload in the Web tab), OkHttp 4.12.0 and Apache Commons Net 3.11.1.

## Testing without a console

[`fake_console.py`](../fake_console.py) pretends to be a console on the same phone, so you can try every tab before you go near the real one. Install Termux, then:

```sh
pkg install python
pip install pyftpdlib
python fake_console.py
```

In Bento, tap the console card and enter `127.0.0.1`. The three lights turn on.

| Tab | What the fake console does |
| --- | --- |
| Install | Listens on 12800, understands both request styles (`/upload` and `/api/install`) and prints the link. For a link from **Install from this phone** it then downloads the file the way a console would: `HEAD`, a few byte ranges, a range past the end (which must be refused with `416`), then the whole file, capped at 64 MB unless you set `FAKE_PS5_FULL=1`. It prints `self-test PASSED`, or why it failed. |
| Payload | Listens on 9021 (a PS5's loader) and 9090 (a PS4's), saving what it receives to `~/fake_ps5/payloads/`, one file per payload. Payload chains send to it the same way. |
| Memory | A fake ps5debug-NG on **7744** (Android does not let Termux open port 744, so set the Memory tab's port to 7744). It runs a pretend game, *Fake Game* (pid 91), with a heap whose health (4 bytes at `20001234`) counts down by 7 every 4 seconds, gold 500 at `20002000` and a float 1.0 at `20003000`. Scans work like the real ones, so you can find the health, freeze it and watch it stop going down. Its eboot holds a pointer path to the health, `[eboot+5100] +10 → +34`, for **Find pointer**. A made-up console log comes out of port 3232. |
| Upload and Files | An anonymous FTP server on 2121 rooted at `~/fake_ps5/ftp`, with a few sample files and folders to open, rename, download and delete. Uploads land in `~/fake_ps5/ftp/data/`. Two pretend games (`CUSA01234` with a `param.sfo`, `PPSA01234` with a `param.json`) with icons, saves for both, and a screenshot and a clip in `/user/av_contents`. |
| Check | `http://127.0.0.1:12800/sample.pkg` is a pretend PS4 package (name, icon, version, firmware) served with byte ranges, for the Install tab's **Check**. |

**Try Find my console.** The fake console listens on all of the phone's addresses, so with the phone on Wi-Fi, **Find my console** finds the phone itself, with Installer, Loader and FTP answering. Tap **Use** and the console card turns green. (On a phone that isn't on Wi-Fi the window says so.)

It shows that the phone side works. It can't show that a real console accepts the same requests.

## Troubleshooting

- **"Can't reach the console".** Nothing answers at that address. Check the IP, that the console is awake on the same Wi-Fi, and that etaHEN (PS5) or GoldHEN (PS4) is running. If the address may have changed, tap **Find my console**.
- **"Find my console" finds nothing.** Turn the console fully on (rest mode often turns the network off), start etaHEN (PS5) or GoldHEN (PS4), and use the same Wi-Fi on the phone and the console. Guest networks and VPNs keep devices apart. Then scan again, or type the address.
- **"Partly ready · off: Installer" (or Loader, FTP).** That service isn't answering. Start it on the console, or fix its port: the installer and loader ports are on the Install and Payload tabs, the FTP port is in the FTP settings of the Upload and Files tabs.
- **"Not accepted (upload … ; api …)".** The console answered, but not in a way Bento recognizes. If `upload` shows `200`, the console may have accepted the first request anyway, so look for a new task on the console. If both show errors such as `404`, that port isn't an installer: check the installer port field and that the installer service is running.
- **"Installer accepted it", but nothing installs.** The console couldn't fetch the link. It has to be a direct link that needs no login and is reachable from the console. Try **Install from this phone**, or send the file over FTP.
- **Some links in the Install box were not sent.** Only addresses that start with `http://` or `https://` are sent. The note under the box says how many words were left out; check that the links are complete.
- **Install from this phone never starts downloading.** The Wi-Fi may be stopping devices from reaching each other (guest networks often do). Use the main network, and let Bento run in the background. To test the link, tap **Copy link** and open it from another device on the same network.
- **"Can't find this phone's Wi-Fi address".** Connect the phone to the same Wi-Fi as the console.
- **"Can't reach the FTP server…".** Wrong FTP port. On a PS5, etaHEN's built-in FTP uses 1337 and the FTP payload uses 2121; on a PS4, GoldHEN's FTP uses 2121.
- **"FTP login failed".** Check **Username** and **Password**. The default is `anonymous` with no password. The password isn't saved, so type it again after restarting the app.
- **"Gave up after the connection kept failing".** The Wi-Fi dropped, or the console went to sleep, for longer than the retries lasted. Tap **Try again**: it continues from where it stopped.
- **"The console kept only X of the Y that were sent".** The console isn't storing everything it receives. Check that it has free space, then try again.
- **A transfer is slow.** Look at the **Speed limit** button on the transfer card; a limit may be set. Choose **No limit** to remove it.
- **A transfer does nothing.** It may be paused: the card says **Paused** and the button next to **Cancel** reads as a play button. Tap it to resume.
- **A payload chain stopped at a step.** The chain card names the step and the reason. "Not on the shelf" means the payload was removed: add it again or edit the chain. If the loader didn't answer, check that the loader is running on the console.
- **"Unfinished from last time" appears again after a restart.** The app was closed before the queue was empty. **Continue** starts those transfers again from the beginning; **Discard** forgets them.
- **The update notice doesn't appear, or "Failed: HTTP 403".** GitHub limited the check. Try **Check now** later, or open the Releases page from the About window.
- **"That file is not a Bento backup".** The file isn't one that Bento's **Save backup** wrote. A backup from a newer Bento needs the newer app first.
- **"The site is busy or down right now (error 503)".** That is the website, not Bento. Try again later.
- **"Couldn't load page: net::ERR_…".** The page, or a redirect it uses, can't be reached from your network. Try **Home** or another link.
- **"Leave this site?" keeps appearing.** Pages with ads try to bounce you to other sites. Tap **Block**.
- **Pulling down reloads a page when I only wanted to scroll.** Some pages scroll inside a box, so Bento can't tell that the page isn't at its top. Scroll with the page itself, or use **Reload**.
- **No progress notification (Android 13 and newer).** Allow notifications for Bento. Transfers run without them, but you won't see them in the shade.
- **"App not installed" when updating.** The installed copy was signed with a different key (a build from somewhere else). Uninstall it first, which loses its saved settings, then install the new one.

## Status and known limits

- **The big update's newest parts are not confirmed on a phone yet.** Learn and the practice game, the GitHub search, game updates from the patch sites, game sizes, copying captures, etaHEN settings, the text editor, zip downloads, installing a folder from the phone, the weekly backup and the look settings were checked on a computer (the practice debugger against Bento's own client code, the parsers against real lists) but not used on a phone and a console yet. **Copy debug info** in the About window helps with a bug report.
- **Game updates depend on the patch sites.** Orbis Patches and Prospero Patches are run by the community; if one changes how its pages work, the check says it has no information until Bento is updated.

- **Mostly not confirmed on a real console yet.** FTP uploads have been confirmed on the author's real PS4 (GoldHEN). Everything else has been tried on a phone only against the fake console: installs, payloads and downloads on a real console, the whole PS5 path, and a real console pulling a package from the phone's server.
- **The newest features are not confirmed on a phone yet.** Find my console, payload chains and their shortcuts, pausing and the speed limit, the offer to continue unfinished transfers, the update check, backup and restore, and the newer parts of the Files tab (selecting, folder downloads, sort, search, pinned folders) and of the Web tab (tabs, the link menu, find in page, the desktop-site switch, pull to reload) were written together with automated tests and checked on a computer: the logic against test servers and fake pages, and the whole app against stand-ins for the Android libraries. That is not the same as using them on a phone, so some rough edges are possible. **Copy debug info** in the About window helps with a bug report.
- **The install requests are based on documentation.** The two styles and the replies Bento looks for come from the public documentation of existing tools, not from watching a real console. If **Install > Send** says "Not accepted", the status line shows what the console actually replied.
- **How the console downloads from the phone is unconfirmed.** The server supports `HEAD` and byte ranges, but what a real console sends hasn't been observed.
- **Wi-Fi can get in the way.** A guest or isolated network can stop the console from reaching the phone, and can hide the console from **Find my console**.
- **Nothing is encrypted.** FTP and the install requests are plain text on your local network, and the app allows cleartext traffic for that reason.
- **Translations are unreviewed.** They have not been checked by native speakers, so some wording may be off.
- **Debug-signed, outside Google Play.** Bento tells you when a newer build is on the Releases page, but it can't install it by itself: you download the APK and open it.
- **Find my console is a best guess.** It lists the devices on your Wi-Fi that answer on the console's usual ports. A device that only has an FTP server could be anything, so check the address. There is no list of the games installed on the console; Bento sends things to the console, it doesn't manage its library.
- **Continuing after the app was closed starts again from the beginning.** The resume described under [Transfers](#transfers) is for dropped connections and pauses while the app is running. The list of unfinished transfers holds at most 200.
- **Downloading folders has limits.** At most 2,000 files from 1,000 folders, 12 levels deep; links inside folders are skipped. A folder that can't be read stops the whole plan, with an error that names it.
- **Browser tabs are not saved when the app is closed.** They survive rotating the phone, but not closing the app. The pages of tabs in the background are paused where Android allows, not frozen.
- **Pulling down on a web page can start a reload when you meant to scroll,** on pages that scroll inside a box rather than the page itself.
- **Pull to refresh in the Files tab is the newest addition.** It builds, but it hasn't been tried on a phone yet.
- **Bento doesn't jailbreak anything** and doesn't include payloads, packages or firmware.

## Privacy and security

- Bento has no accounts, no analytics and no ads of its own. It talks to the console you set up, to the web pages you open in the Web tab, to the links you ask it to fetch, to GitHub (the update check and payload lists) and, on request, to the community patch sites.
- Permissions: internet access; foreground service (for background transfers, sharing and payload chains); notifications (Android 13 and newer); and wake lock (to keep the processor and Wi-Fi awake during a transfer, while sharing from the phone and while a payload chain runs). It does not ask for storage, location, contacts or the camera. **Find my console** needs no extra permission.
- Settings are stored in the app's private storage: consoles and their ports, the FTP user name and folder, bookmarks, the homepage, the ad-block switch and your ad-block list, the desktop-site switch, payload chains, pinned folders, the folder last viewed on each console, the sort order, the speed limit, the ten recent install links, and the update-check settings. The FTP password is never saved. The payload shelf is also in private storage.
- **Unfinished transfers** are written to a file (`unfinished-transfers.json`) in the app's private storage that Android leaves out of its backups. It holds the names, console addresses, FTP user names, folders, file locations and download links of transfers that were not finished, never a password or a cookie, and it is emptied when the queue is.
- **The update check** asks GitHub for the newest release of this repository: when the app opens, at most once a day, while the switch in the About window is on, and whenever you tap **Check now**. GitHub sees your IP address and the app's name, as any website would. Nothing about you or your consoles is sent.
- **Find my console** only runs when you tap it. It opens a connection to a few console ports on the addresses of your own Wi-Fi network and closes it again. Nothing is sent, and nothing leaves your network.
- **Backups** are plain files that you save yourself. They hold your consoles' names, addresses and FTP user names, bookmarks, ad-block list, payloads, chains and pinned folders, never FTP passwords. Keep the file where you keep other private things.
- **The debug info** (About window, **Copy debug info** or **Share debug info**, and the connection check) leaves out web links and IP addresses, and it is shown on screen before you paste it anywhere.
- **Get payloads** reads Bento's own list from this repository, the PS5 Payloads Mirror's list and, when you ask, GitHub's search. **Game updates** sends a game's title ID to Orbis Patches or Prospero Patches, and only when you tap **Check for updates**. Sony's servers are never contacted by Bento, from the phone or the console.
- **The practice game** runs inside the app on this phone only (127.0.0.1); nothing outside the phone can reach it.
- The browser's open tabs (their addresses and titles) are held in memory, in the state Android keeps while the screen is rebuilt. Bento doesn't write them to its storage.
- When you relay a web download, Bento sends the browser's cookies for that link with the request, so links that need a login session work.
- "Install from this phone" serves only the files you picked, only while sharing runs, only on the one address of the phone that the console can reach, and only under links with a random token. Anyone on the same network who has a link can download that file, and the connection isn't encrypted.
- The app is signed with a public debug key. Install it only from your own build or from this repository's Releases.

## Responsible use and license

Bento is a tool for moving data to **your own** console. It doesn't contain or host games, firmware, keys or other copyrighted material, and it doesn't jailbreak anything: it only talks to services that a jailbroken console already runs. Use it only with content you have the right to use. The Web tab is a general browser; its start page is a third-party site picked by the author, which Bento is not affiliated with and doesn't vouch for. You are responsible for what you open, download, install and send.

Sending a payload or deleting files on a console can have consequences: a bad payload can crash it, and a deleted file stays deleted. Use Bento at your own risk. It is provided as is, without warranty.

PlayStation, PS4 and PS5 are trademarks of Sony Interactive Entertainment Inc. Android is a trademark of Google LLC. Bento is an independent hobby project and is not affiliated with, endorsed by or sponsored by either company.

**License.** Bento is released under the [MIT License](../LICENSE): you may use, copy, change and share it, as long as the copyright notice and the license text go along with it. The libraries and tools in the credits keep their own licenses.

## Credits

Bento exists because of other people's work. Thank you to everyone below.

**Made by** DubDeezo.

### On the console: what Bento talks to

Bento is only a client. Everything it connects to on the console was made by others, and none of it is part of Bento.

- **[etaHEN](https://github.com/etaHEN/etaHEN)** by **LightningMods** and contributors (see its README for the full list), GPL-3.0. Its Direct Package Installer (the V2 web interface on port 12800), its ELF loader and its built-in FTP server are what Bento's Install, Payload, Upload and Files tabs talk to on a PS5.
- **[ps5-payload-dev/elfldr](https://github.com/ps5-payload-dev/elfldr)** and **[ps5-payload-dev/ftpsrv](https://github.com/ps5-payload-dev/ftpsrv)** by **John Törnblom** and contributors, GPLv3+: the ELF loader (port 9021) and the FTP server (default port 2121).
- **[GoldHEN](https://github.com/GoldHEN/GoldHEN)** by **SiSTR0** and contributors, GPL-3.0: on a PS4 its ELF loader (port 9090) and FTP server (port 2121) are what Bento's Payload, Upload and Files tabs talk to.
- **[flatz/ps4_remote_pkg_installer](https://github.com/flatz/ps4_remote_pkg_installer)**, the Remote Package Installer by **flatz**: the package installer Bento talks to on a PS4, and the second style it tries on a PS5. Its `/api/install` request takes `{"type":"direct","packages":[…]}` with the link as plain text.
- **[MaxMilu/ps5-direct-package-installer](https://github.com/MaxMilu/ps5-direct-package-installer)**, an unofficial derivative of etaHEN's installer (GPL-3.0-or-later). Its documentation of the port 12800 web interface (`POST /upload` with a `url` field) matches the first request Bento sends.
- Wololo.net: [etaHEN 1.7b adds Remote Package Install support](https://wololo.net/2024/02/26/ps5-release-etahen-1-7b-adds-remote-package-install-support/) (26 February 2024), where etaHEN's installer and its public API were announced, and [etaHEN 2.0b and ItemzFlow 1.09 released + PKG install Writeup by LightningMods](https://wololo.net/2025/03/31/ps5-etahen-2-0b-and-itemzflow-1-09-released-pkg-install-writeup-by-lightningmods/) (31 March 2025), which added the Direct Package Installer V2 (web GUI version) to etaHEN's services menu.

### For the Memory tab

- **[ps5debug-NG](https://github.com/Pharaoh2k/ps5debug-NG)** by **OpenSourcereR** (OSR), Pharaoh2k and contributors, GPL-3.0, based on Ctn and SiSTRo's ps5debug and jogolden's ps4debug: the debugger payload the Memory tab talks to. Bento implements its wire protocol from its PROTOCOL.md and source; it contains none of its code.
- **[etaHEN/PS5_Cheats](https://github.com/etaHEN/PS5_Cheats)**, the community cheat files (continued from the GoldHEN cheat repository), and everyone credited in each file: the ready-made cheats of the Memory tab. Bento downloads them when asked; they are not part of the app.

### Inspiration

- **NovaPS**, a PS4 homebrew app, on its [Dekazeta page](https://www.dekazeta.net/foro/files/file/4906-novaps/). Bento was inspired by it. It is a separate project written from scratch for the PS5 and the PS4 and contains no NovaPS code.

### Built into the app

- **[Kotlin](https://kotlinlang.org/)** by JetBrains, Apache-2.0.
- **[Jetpack Compose, Material 3 and AndroidX](https://developer.android.com/jetpack/compose)** by Google, Apache-2.0. The Web tab uses Android's own WebView.
- **[OkHttp](https://github.com/square/okhttp)** 4.12.0 by Square and contributors, Apache-2.0: install requests and web downloads.
- **[Apache Commons Net](https://commons.apache.org/proper/commons-net/)** 3.11.1 by the Apache Software Foundation, Apache-2.0: the FTP client.
- **[AndroidX SwipeRefreshLayout](https://developer.android.com/jetpack/androidx/releases/swiperefreshlayout)** 1.1.0 by Google, Apache-2.0: pull down to reload in the Web tab.

### Build and delivery

- **Android Gradle Plugin** and **[Gradle](https://gradle.org/)**, with Eclipse **Temurin** JDK 17.
- **[GitHub Actions](https://github.com/features/actions)** (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, `actions/upload-artifact`) and the **[GitHub CLI](https://cli.github.com/)** (`gh`) for the Releases page.
- **[GitHub's Releases pages](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)**, which the update check in the app reads.

### Testing

- **[Termux](https://github.com/termux/termux-app)**, which runs the fake console on the phone.
- **[pyftpdlib](https://github.com/giampaolo/pyftpdlib)** by Giampaolo Rodolà, MIT: the FTP server inside `fake_console.py`.

### Standards

[RFC 959](https://www.rfc-editor.org/rfc/rfc959) (FTP), [RFC 3659](https://www.rfc-editor.org/rfc/rfc3659) (`SIZE` and `REST`), [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) (HTTP: `Range`, `If-Range`, `ETag`, 206 and 416) and [Unicode UAX #9](https://www.unicode.org/reports/tr9/) (bidirectional text, for the right-to-left isolates).
