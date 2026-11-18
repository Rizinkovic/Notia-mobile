# Notia Mobile (Android, native Kotlin)

Offline notes, tasks with alarms, library, password vault and Wi-Fi transfer. Native Kotlin on plain
Android Views: no Compose, no AndroidX, one tiny dependency (Play-services QR scanner).
Package `com.notia.mobile`, Android 7.0+.

## Get the release APK from GitHub
1. Push this tree to the `mobile-app` branch. The workflow `.github/workflows/android-release.yml` runs automatically
   (or run it from **Actions → Build Notia APK (release) → Run workflow**).
2. Download `Notia-<version>-build<N>.apk` from the run's **Artifacts**, or from **Releases**.

### Signing
* Default: the bundled `app/notia-debug.keystore` (same key as the old Expo build, so the APK installs over it and keeps your notes).
* Your own key (recommended once you publish): add repository secrets `NOTIA_KEYSTORE_BASE64` (`base64 -w0 my.jks`),
  `NOTIA_KEYSTORE_PASSWORD`, `NOTIA_KEY_ALIAS`, `NOTIA_KEY_PASSWORD`. Switching keys later means a one-time uninstall.

## Build locally
`gradle :app:assembleRelease` (JDK 17, Android SDK 34) → `app/build/outputs/apk/release/app-release.apk`

## Features
Notes (rich editor: headings, bold/italic/underline, lists, code, lines, colours, highlights, images, links, tables,
formulas, charts, undo/redo; categories; search; character count; created/modified), export to PDF and DOCX, share as
text/PDF/DOCX; **tasks with date and time and an alarm-style notification (ring, vibrate, Done and Snooze buttons;
survives reboot and app updates)**; library of 10 books (PDF, EPUB, MOBI/AZW, DOCX, TXT, HTML) with covers, progress,
reading time, bookmarks, highlights, **table of contents**, daily "finish your book" reminder; vault (AES-256-GCM in the
Android Keystore, PIN or password, lockout, screenshot-blocked); Wi-Fi send/receive and desktop QR sync (same protocol
as the desktop app); dark/light; English/French.

**Reminders (Settings → Reminders):** a morning "write a note or an idea" nudge (time of your choice, opens a blank note when tapped,
skipped if you already wrote today), a "come back" nudge after 3, 7, 14 and 30 days without opening the app, and an evening
"finish your book" nudge (skipped if you already read today). Each one can be switched off. **Settings** also has About, Privacy
policy, version info and developer contact.

**Note editor toolbar:** one comfortable row of basics (undo, redo, bold, italic, underline, lists); the arrow at its end opens
all the other tools (headings, code, line, link, image, table, formula, chart, text colour, highlight, clear formatting). The choice is remembered.

## Open in Notia
Tap a PDF, EPUB, MOBI/AZW, DOCX or TXT file in any file manager, mail or chat app and choose **Notia** (or use
**Share → Notia**). The book is added to the library once (opening the same file again reuses it) and opens straight
in the reader.

## Upgrading from the Expo build
Notes, categories, tasks and library (including book files and covers) are migrated automatically on first launch.
The password vault cannot be migrated (the old one used a different secure store): create the vault again.

## Notes
* PDFs have no highlight (they are images); bookmarks and page jump work. Other formats support highlights.
* QR scanning uses Google Play services. Without it, paste the sync link instead.
* For on-time reminders Android 12+ may ask to allow "Alarms & reminders"; Notia shows a prompt for it.

## Changelog
### 1.0.0
* Version corrected to 1.0.0 (versionCode is unchanged so it still installs over earlier builds).
* Fixed header buttons (back, transfer, lock, share): icon buttons were being stretched to the full row width. Fixed at the source in `LinearLayout.put`.
* Fixed tasks: new tasks and the suggested times (In 1 hour, Tonight 20:00, Tomorrow 09:00) now save, stay highlighted and confirm with a message.
* New Settings page: reminders, About, Privacy policy, version, website notia.srichard.site, contact rsongrichard@gmail.com.
* New notifications: morning writing reminder, come-back reminder, finish-your-book reminder. Permission is requested once on first launch (Android 13+).
* Redesigned note toolbar (basics / all tools) and a glass-style reader toolbar in the library.
* Category chips line up with the page margin; note list recycles its views.
