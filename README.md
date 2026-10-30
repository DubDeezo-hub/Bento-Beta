<p align="center">
  <img src="bento-icon.png" width="128" height="128" alt="Bento app icon: a cyan and black bento box">
</p>

<h1 align="center">Bento</h1>

<p align="center">
  <b>Your Android phone's remote for a jailbroken PS5 or PS4.</b><br>
  Install games and apps, send payloads, move files and cheat in games, all over Wi-Fi.<br>
  by DubDeezo
</p>

<p align="center">
  <a href="https://github.com/DubDeezo-hub/Bento-Beta/releases/latest"><b>Download the latest APK</b></a> ·
  <a href="docs/GUIDE.md">Full guide</a> ·
  <a href="#troubleshooting">Troubleshooting</a>
</p>

## What it does

| Tab | |
| --- | --- |
| **Install** | Install `.pkg` files from a web link (or a whole list), or straight from your phone. **Check** shows a package's name, icon, version and needed firmware first, and whether it is new, an update or already installed. |
| **Payload** | Send `.elf` / `.bin` payloads. Keep favourites on a shelf, run several in a row as a **chain**, and **get payloads** from their makers' GitHub pages: Bento's picks (etaHEN, ps5debug-NG, ftpsrv…), a community list of 30+ PS5 payloads and a GitHub search. Bento checks for new payloads and updates when it starts and marks which ones list your firmware. |
| **Upload** | Copy files from your phone to the console over FTP, in the background. **Keep a folder in sync**: only new or changed files are sent. |
| **Files** | Browse the console's files (download, rename, delete, search, pin). See your **installed games** with names and icons, **back up and restore saves**, and grab **screenshots & videos**. |
| **Web** | A browser for homebrew sites, with tabs, bookmarks and ad block. Send any link to the console or download it. |
| **Memory** | A cheat engine for PS5 and PS4: find a value (health, money), change or **freeze** it, find **pointer paths** that survive a restart, switch on ready-made cheats, **make your own cheat file**, **find text** (names, items), **undo** any change, and read the **console log**. |

Every option has a small **?** that explains it, and a welcome page walks you through the first start. Something not working? **What's wrong?** on the console card tests each part of the connection and says how to fix it. The app's language can be picked inside the app (53 languages).

## What you need

- An Android phone (Android 10 or newer).
- A jailbroken console: **PS5 with etaHEN** or **PS4 with GoldHEN**.
- Both on the **same Wi-Fi** (not a guest network).
- For the Memory tab: the **[ps5debug-NG](https://github.com/Pharaoh2k/ps5debug-NG)** payload on a PS5, or **[ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG)** on a PS4 (both are in **Get payloads** on the Payload tab).

## Get started

1. Download the newest `Bento-1.N.apk` from **[Releases](https://github.com/DubDeezo-hub/Bento-Beta/releases)** and open it on your phone. New versions install over the old one and keep your settings; Bento tells you when one is out.
2. Open Bento and tap the card at the top. Enter the console's IP address, or tap **Find my console**.
3. When the lights on the card turn green, pick a tab.

**Cheating in a game, quickly:** send `ps5debug-NG.elf` (or `ps4debug-ng.bin` on a PS4) from the Payload tab → Memory → **Connect** → search the number the game shows (4 bytes, Exact value) → change it in the game → **Next scan** with the new number → tap the match that's left → write a new value or **Freeze** it.

## Troubleshooting

- **Not sure what's wrong:** tap **What's wrong?** on the console card. It checks the phone's Wi-Fi, the network, each service's port and the debugger, and offers a fix (such as using the port that answers). **Copy debug info** gives a helper everything they need, without IP addresses or links.
- **Can't reach the console:** check the IP, that the console is awake (not in rest mode) and that etaHEN / GoldHEN is running. **Find my console** finds a changed address.
- **A light stays off:** that service isn't running on the console, or its port is different. Ports are under **Settings** at the bottom of each tab.
- **Installer accepted it, but nothing installs:** the link must be a direct download the console can open without logging in. Try **Install from this phone**.
- **FTP fails:** etaHEN's own FTP is port 1337, the ftpsrv payload and GoldHEN use 2121. User `anonymous`, no password.
- **Memory tab: "Nothing answered on port 744":** send the debugger payload first and wait for its notification on the TV.
- **A cheat stops working after a restart:** addresses move. Tap **Find all again**, or give the cheat a **pointer path** (open it → Find pointer).
- **Games & apps or Saves list is empty:** the FTP server must show the whole file system (etaHEN's FTP and ftpsrv do). Saves only go back onto the same console and user.
- **"App not installed" when updating:** a copy from somewhere else is installed. Uninstall it first.

More answers, and what every message means, are in the [full guide](docs/GUIDE.md#troubleshooting).

## Try it without a console

[`fake_console.py`](fake_console.py) pretends to be a console on the same phone, Memory tab included:

```sh
pkg install python && pip install pyftpdlib   # in Termux
python fake_console.py
```

In Bento use `127.0.0.1` as the address, and port `7744` on the Memory tab. Its pretend game loses health every few seconds, so you can find it, freeze it and find its pointer path (`[eboot+5100] +10 → +34`). It also has two pretend games with saves and screenshots for the Files tab, a console log on port 3232, a sample package at `http://127.0.0.1:12800/sample.pkg` for **Check**, and a cheat file at `http://127.0.0.1:12800/cheats/`.

## Build it yourself

Fork the repo, open **Actions**, run **Build APK**. The APK lands on your Releases page. Every push to `main` builds one too, and **Phone test** runs the app on a virtual phone in all 53 languages. Details: [Build, signing and releases](docs/GUIDE.md#build-signing-and-releases).

## Good to know

- **Status:** FTP is confirmed on a real PS4. The PS5 paths, the Memory tab, the games list, saves and the payload store have been tested against the fake console and the published formats and protocols, not yet on a real console.
- **Tablets:** on a wide screen the tabs move to the left side and transfers get their own pane on the right.
- **Online games:** use the Memory tab offline only. Changing memory in online games can get your account banned.
- **Privacy:** no accounts, no analytics, no ads. Bento talks only to your console, the pages you open and GitHub (update check). Nothing is encrypted on your local network (FTP is plain text). [More](docs/GUIDE.md#privacy-and-security).
- Bento doesn't jailbreak anything and contains no games, firmware or payloads. Use it with content you have the right to use, at your own risk.

## Credits

Bento is only a client. It talks to **[etaHEN](https://github.com/etaHEN/etaHEN)** (LightningMods), **[GoldHEN](https://github.com/GoldHEN/GoldHEN)** (SiSTR0), **[elfldr / ftpsrv](https://github.com/ps5-payload-dev)** (John Törnblom), flatz's **[Remote Package Installer](https://github.com/flatz/ps4_remote_pkg_installer)**, **[ps5debug-NG](https://github.com/Pharaoh2k/ps5debug-NG)** and **[ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG)** (OpenSourcereR, Pharaoh2k) and the community **[PS5_Cheats](https://github.com/etaHEN/PS5_Cheats)** files. Inspired by NovaPS. Built with Kotlin, Jetpack Compose, OkHttp and Apache Commons Net. Full list: [credits](docs/GUIDE.md#credits).

MIT License. PlayStation, PS4 and PS5 are trademarks of Sony Interactive Entertainment; Bento is an independent project, not affiliated with Sony.
