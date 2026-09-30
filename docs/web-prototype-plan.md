# Plan: Server prototype (connectors, no C64 needed)

*Status: plan v3 · Date: 2026-09-30 · Architecture and API: [spec.md](spec.md) · Setup: [setup-guide.md](setup-guide.md)*

## 1. Goal

Build the image server from [spec.md](spec.md) far enough that everything can be tested **from a browser**, before any C64 code exists:

* The **admin page** shows every channel's status and a preview of its current image: the original next to the C64 rendering, drawn from the exact bytes the C64 will get.
* Google sign-in and photo picking run **on the server**, started from the admin page.
* `/api/image` already speaks the C64 protocol (header, "unchanged", message images). It can be checked with `curl` and in VICE through `raw=1`.
* **No images are stored** outside RAM.

## 2. What gets built, in order

| Step | Content | Done when | Days |
|---|---|---|---|
| 1 | **Core:** config file loader, channel registry, playlist with per-client cursor, render pipeline (`fit` / `center` crop → convert), message renderer with QR, built-in fallback image, `/api/image` with header / "unchanged" / `nav` / per-client position, `raw=1` | A test channel returns a message image; a repeat request with `have=` returns "unchanged" | 2–2.5 |
| 2 | **Local folder connector + admin page** with channel list, status and previews (original / C64, mode/dither/crop toggles, "show only this channel") | Photos from a folder on the Mac rotate in the preview; `raw=1` output opens in VICE | 1–1.5 |
| 2b | **Smart crop**, first version: pure-Java scoring window, judged on your own photos | Portraits and landscapes crop to the interesting part; `crop=fit/center/smart` all work in the preview | 1 |
| 3 | **Wikipedia picture of the day** | Today's picture shows; "unchanged" until midnight; credit on `/api/info` | 0.5–1 |
| 4 | **Google Photos:** "Sign in with Google" on the admin page → relay page → callback → "Pick photos" → picker; RAM tokens, re-listing after 50 min, `NEEDS_USER_ACTION` message image with QR when the session or sign-in expires | Photos picked on the phone rotate in the preview; after expiry the preview shows the QR message | 2 |
| 5 | **Nano Banana:** prompt template with date/time placeholders, schedule, `maxPerDay`, "Generate now" | Three scheduled pictures a day; the cap stops a fourth | 1 |
| **Total** | | | **7.5–9** |

Steps 3–5 are independent of each other; each can be dropped or reordered.

## 3. Technology

* **Java, in this repository**, new package (for example `com.sixtyfour.c64server`). It reuses Petsciiator for conversion and Jackson for JSON and YAML (`jackson-dataformat-yaml`), and `java.net.http.HttpClient` for Google, Gemini and Wikimedia. ZXing draws the QR codes.
* **Runs on the Mac mini** with `mvn jetty:run` (Jetty 9.4, matches `javax.servlet` 3.1). The old `ImageViewer` servlet stays untouched.
* **Config** in `~/c64server/config.yaml` (example in spec §4); secrets in environment variables.
* **Google sign-in** returns through the static page on `ursiny.cz`, which forwards the one-time code to the Mac's fixed address. Only the Google Photos connector needs this.

## 4. Google Photos limits (still valid)

| Limit | Effect |
|---|---|
| Image links expire after 60 min | None visible: the server re-lists the picker session |
| Access token expires after 1 h | None visible: refreshed silently |
| Picker session expires (reportedly **~7 days**) or the sign-in is refused (7 days in Testing mode) | Channel status becomes "needs action". The C64 shows a message image with a QR code to the admin page, where you sign in and pick again. **This is the "re-scan when Google requires it" policy, now handled on the server.** |
| Picker selects photos, not folders | Search the album name in the picker and select its photos. New photos in that album appear only after the next pick. |

## 5. Test without a C64

* **Admin page previews:** the main check, with the same pipeline and bytes as the C64 gets.
* **`curl "http://<mac>:8080/api/image?mode=mc&crop=smart" | xxd | head`:** checks the header (`C6`, version, type, flags, id).
* **`curl ".../api/image?raw=1" > current.koa` (use `solo` on the admin page to pick which channel):** open it in VICE, or view it with the `localviewer` BASIC program in this repo.
* **The same request with `have=<id>`:** must return the 10-byte "unchanged" header.

## 6. Questions the prototype must answer

1. The real Picker session lifetime and Testing-mode sign-in lifetime (measured and logged by the server).
2. Conversion time per image on the Mac mini, and whether the RAM cache keeps next/previous instant.
3. The exact Wikimedia JSON fields, and how videos and SVGs come through.
4. The current Nano Banana model id and price, and how well generated pictures survive 16-colour conversion.
5. Whether the WiC64 resolves `.local` names (a quick test once the C64 client exists; until then the Mac uses a fixed IP).
