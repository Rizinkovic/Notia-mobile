# Notia Mobile (Android, native Kotlin)

Offline notes, tasks with alarms, library, password vault and Wi-Fi transfer. Native Kotlin on plain
Android Views: no Compose, no AndroidX, three small dependencies (Play-services QR scanner and document scanner, PDFBox for reading the text of PDFs).
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
text/PDF/DOCX; **tasks with an alarm (type a task, pick e.g. 8 PM, and the phone rings at that minute even asleep; Done and Snooze
buttons; tap a task to add, change or remove its alarm; survives reboot and app updates)**; library of 10 books (PDF, EPUB,
MOBI/AZW, **CBZ comics**, DOCX, TXT, HTML) with covers, progress, reading time, bookmarks, **coloured highlights**, **table of contents**,
**zoom** (pinch with two fingers; double-tap on PDFs) and **select and copy text** (PDF and ebooks), daily "finish your book" reminder; vault (AES-256-GCM in the
Android Keystore, PIN or password, lockout, screenshot-blocked); Wi-Fi send/receive, desktop QR sync (same protocol
as Notia Desktop) and phone-to-desktop send by scanning the desktop QR code; dark/light; English/French.

**Reminders (Settings → Reminders):** a "come back" nudge after 3, 7, 14 and 30 days without opening the app, and an evening
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

## Reading, zoom, copy and highlight
* **PDF:** pinch with two fingers (or double-tap) to zoom, 1x to 5x, always sharp: pages are drawn at screen resolution in the background, a little ahead of your scrolling. Long-press a word to select,
  drag the two handles to extend, then **Copy** or tap a colour to highlight. "All" selects the page. Scanned PDFs have no text layer to select.
* **EPUB / MOBI / DOCX / TXT:** pinching changes the text size and reflows the page; the size is remembered. Select text for Copy or a colour.
* **CBZ comics:** pages are shown top to bottom; pinching widens the pages, drag to pan.
* Highlights are saved per book (Contents panel → Highlights).

## Notes
* QR scanning uses Google Play services. Without it, paste the sync link instead. Phone and PC must be on the same Wi-Fi.
* Task alarms use Android's alarm-clock API, so they ring on time even in Doze and need no extra permission (only notifications on Android 13+).
* Sending notes **to the desktop** needs the matching Notia Desktop update ("Receive notes from phone (QR)"); see `notia-desktop-receive-from-phone.patch`.
* PDF text selection is read by PDFBox, which adds a few MB to the APK.

## Changelog
### 1.1.2
* **Scan to PDF (Notes → scan icon):** the Google document scanner (automatic page detection, crop, perspective fix, filters, several pages) turns paper into one PDF, like Notes on iPhone. Share it, save it to the phone, or keep the pages as a note. Pages are stored at about 200 dpi JPEG, a few hundred KB each. Without Google Play services the app offers to build the PDF from gallery photos instead. The same scanner is in the editor's tools ("Scan pages") and inserts the pages into the current note.
* **Exports are small:** PDF export is a new writer (built-in PDF fonts, nothing embedded; pictures downscaled to about 150 dpi JPEG; text stays selectable). A note with a few lines is about 3 KB and one with a photo a few hundred KB, instead of 6 MB or more (Android's PdfDocument embedded whole system fonts and stored pictures uncompressed). DOCX pictures are now shrunk to 1100 px JPEG too. Text outside the Latin set (Chinese, Arabic, emoji ...) is drawn as small pictures in the PDF.
* **Picture size:** tap a picture in a note to select it and drag the round handle; tap it again (or use the resize button in the tools) for Small / Medium / Large / Full, a width slider, left / centre / right alignment and delete. The chosen size is used in PDF and DOCX exports.
* **Justify and align:** the tools panel has align left, centre, right and justify; exports keep them (PDF and DOCX).
* **Library details:** three dots on each book show name, file type, author (read from EPUB, MOBI/AZW, PDF and DOCX metadata) and size.
* **Settings → Contact → Support Developer** opens https://srichard.site/donate.
* **Transfer fixes:** the phone now understands Notia Desktop's Wi-Fi share (scan its code and the exported note or file is downloaded and imported: md / txt / html become notes, pdf / docx / epub ... go to the Library); sync addresses are matched with the desktop's real token length; a sync address that answers with a web page falls back to that file import; requests are retried on the other network route; the "Sync failed" message now says why (timeout, refused, HTTP 404, address not found).

### 1.1.1
* **MOBI / AZW3 / CBZ files now show up:** the file picker no longer filters by MIME type (Android reports these formats under many types, or none), and "Open with Notia" now also matches the generic and Amazon types that Downloads and file managers use. Files with a missing or wrong name are recognised from their first bytes. KF8-only AZW3 files are still not readable (convert to EPUB); normal MOBI files, including MOBI/KF8 hybrids, open.
* **Zoom is pinch only:** the +/- buttons are gone. Ebook pinch is now detected natively, so one-finger scrolling is no longer slowed by a page script.
* **Smoother PDFs:** one render per page at normal zoom, tiles prefetched ahead of the scroll, long PDFs open on their first page's size and are measured in the background, progress saved at most every 150 ms.
* Comic pages are cached while reading.

### 1.1.0
* **Zoom** in PDFs (pinch, double-tap, +/- buttons, 1x-5x, sharp tiles) and in ebooks (pinch/+/- text size, remembered). Comics zoom by page width.
* **Copy text from PDFs** (long-press, handles, Copy, select page) and from ebooks. **Highlights in five colours** in PDFs and ebooks, saved per book.
* **New format: CBZ comics** in the library (EPUB and MOBI/AZW were already supported). "Open with Notia" also accepts .cbz.
* **Tasks:** pick a time of day (e.g. 8 PM) in one tap; tap a task to add, change or remove its alarm. The alarm now rings like an alarm (looping sound,
  up to 3 minutes) and fires on time using the alarm-clock API; the "Alarms & reminders" permission is no longer needed.
* **Settings:** removed the morning writing reminder and the test notification; all contact details (developer, website, e-mail) are in one Contact section.
* **QR sync / transfer:** requests now use the Wi-Fi network even when mobile data is on (fixes "unreachable" on Wi-Fi without internet); clearer errors;
  one scan button understands desktop sync and desktop receive codes; desktop file-share codes offer to open in the browser.
### 1.0.0
* Version corrected to 1.0.0 (versionCode is unchanged so it still installs over earlier builds).
* Fixed header buttons (back, transfer, lock, share): icon buttons were being stretched to the full row width. Fixed at the source in `LinearLayout.put`.
* Fixed tasks: new tasks and the suggested times (In 1 hour, Tonight 20:00, Tomorrow 09:00) now save, stay highlighted and confirm with a message.
* New Settings page: reminders, About, Privacy policy, version, website notia.srichard.site, contact rsongrichard@gmail.com.
* New notifications: morning writing reminder, come-back reminder, finish-your-book reminder. Permission is requested once on first launch (Android 13+).
* Redesigned note toolbar (basics / all tools) and a glass-style reader toolbar in the library.
* Category chips line up with the page margin; note list recycles its views.
