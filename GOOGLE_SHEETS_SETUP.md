# Google Sheet + Google Apps Script Setup (Free / Serverless)

## 1. Create or Upgrade the Google Sheet
1. Open [Google Sheets](https://sheets.google.com) and create a new spreadsheet named **`BSP-SMS Users`** (or open your existing **`BSP-SMS Users`** sheet).
2. Rename the bottom sheet tab from `Sheet1` to **`Users`** (or let `Code.gs` auto-create it on first request).
3. Exact required columns in **Row 1 (`A1:G1`)** — no extra columns are required:
   - `A1`: `user_id`
   - `B1`: `phone_number`
   - `C1`: `password` (stored as salted SHA-256 `sha256s:<salt>:<hash>`)
   - `D1`: `status` (`inactive` or `active`)
   - `E1`: `expiry_date` (`YYYY-MM-DD`)
   - `F1`: `active_device_id`
   - `G1`: `last_login`

## 2. Deploy or Update Google Apps Script
1. Inside the Google Sheet, click **Extensions → Apps Script**.
2. Replace the contents of `Code.gs` with the full contents of `google-apps-script/Code.gs`.
3. Click **Save** (`Ctrl+S`).
4. For a new deployment: click **Deploy → New deployment** → **Web app** (**Execute as:** `Me`, **Who has access:** `Anyone`) → **Deploy** and copy the `/exec` URL.
   For an existing deployment: click **Deploy → Manage deployments → Edit (pencil) → Version: New version → Deploy** so the same `/exec` URL runs the updated script.

## 3. Built-in Apps Script Backend URL
The Android app has your Google Apps Script Web App `/exec` URL permanently built into `AuthRepository.kt` (`APPS_SCRIPT_URL`) and `BSP-SMS-Android/app/build.gradle.kts`, so **Register**, **General Login**, and **Admin Monitor** work immediately on every install with no manual URL configuration required.

## 4. Admin Controls & Per-User Portal Isolation
- **Admin Dashboard & User Management:** Signing in as Admin (`ID: 9679843774`, `Pass: 9933115114`) opens **Admin Monitor** automatically and fetches all users from Google Sheets, showing **Total Users**, **Active**, **Inactive**, **Logged In**, and **Portal Active** counts, with Search and Filters (`All`, `Active`, `Inactive`, `Logged In`, `Expired`).
- **Individual Account Controls:** Tap **Activate (30d)** (sets `status = active`, calculates 30-day `expiry_date`, and clears `active_device_id`), **Deactivate** (sets `status = inactive` and blocks General Login), **Reset Device** (clears `active_device_id` without changing status/expiry), **Edit**, **Password**, or **Delete**.
- **Individual Portal Session Controls:** View each user's live Banglarshiksha portal status (`Portal: Active Session` / `Signed Out`), tap **Force Portal Out** or **Reset Portal** to increment `portal_reset_version` so that user's isolated WebView profile is wiped and returned to the fresh Banglarshiksha Sign In screen on next check or open.
- **Directly in Google Sheets:** Changing `status` (`D`) to `active` runs the Apps Script `onEdit` trigger to assign a 30-day `expiry_date` if empty/expired and clear `active_device_id`. Changing `status` (`D`) to `inactive` automatically clears `app_logged_in` and `session_token`.


## 5. Central Portal URL, App Update & Tool Permissions
The updated `Code.gs` automatically creates two additional tabs when the relevant Admin actions are used:

- **`AppConfig`** (`key`, `value`): `portal_url`, `minimum_version_code`, `latest_version_name`, `apk_download_url`, `update_message`, `force_update`.
- **`ToolPermissions`** (`user_id`, `tool_id`, `enabled`): per-user access for `formative`, `talk`, `lpcd`, `bco`, `excel`, `bulk_marksheet`, `result_ready`, and `hprc_scan`.

After deploying the updated Apps Script as a **new version**, open Admin Monitor:

1. Open **Central App Configuration** and set the HTTPS Banglarshiksha Portal URL. The app refreshes this configuration periodically and applies a changed URL to the mounted Portal WebView.
2. Set the minimum `versionCode`, latest version name, HTTPS APK download URL and update message. Enable **Force update** to block older versions whose installed version code is below the minimum.
3. Open a user's **Manage Tool Permissions**, choose the tools allowed for that account and save. Permission choices are stored in `ToolPermissions`.

Existing accounts default to all eight tools enabled until an Admin explicitly changes a permission. The app must be signed with the same Android signing key for an APK update to install over the existing app. For production mandatory updates, keep a persistent release keystore safe and use it for every release; a newly generated debug signing key is not a dependable update-signing identity.

## 6. HPRC Scan Tool OCR Notes
HPRC Scan uses on-device Bengali + English Tesseract OCR models and does not send report-card images to a cloud OCR service. Printed text is the primary supported case. Handwritten marks/words can be misread, so captured pages remain reviewable and must be checked before using **FILL**. Unmatched or ambiguous student/field matches are skipped; the tool does not submit the Portal form automatically.

## 7. Stable APK Signing for Mandatory Updates
Android will only install an update over an existing app when the new APK is signed with the same signing certificate. Do not use a newly generated debug APK as the permanent update channel.

For a persistent release key, create one keystore once and keep a secure backup:

```bash
keytool -genkeypair -v -keystore bsp-sms-release.jks -alias bsp-sms -keyalg RSA -keysize 2048 -validity 10000
```

Add these repository Actions secrets in GitHub (**Settings → Secrets and variables → Actions**):
- `ANDROID_KEYSTORE_BASE64` — Base64 content of the keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow will then publish `bsp-sms-signed-release-apk` as well as the debug artifact. Use the signed Release APK's stable download URL in Admin → Central App Configuration. Never regenerate or lose the keystore used for installed production releases.

HPRC scan sessions are stored locally in a separate app-private file for each signed-in BSP-SMS account. Scans from one account are not mixed with another account's saved pages on the same phone.
