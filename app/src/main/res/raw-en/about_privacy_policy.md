**Last updated: 2026-11-11**

**Class Plus Plus (Classpp / Class++, "this project")** is a local-first timetable app. This policy explains what data the app processes, why, where it may be sent, and how you can control it.

## 1. What we collect

- **Nothing on our side**: the author does not operate a server that collects user data. The app contains no advertising, analytics, or crash-reporting SDK, and does not read contacts, SMS, location, or the microphone.
- **Local-only data**: timetables, time schemes, todos, tags, schedule background images, AI conversation records and copied images, app settings, and cached remote school declarations are stored on your device by default.
- **Sensitive credentials**: school account passwords and AI API keys are encrypted with the Android Keystore and stored in the app's private directory (credential saving is supported by only some schools). Session cookies created by WebView login are stored in the app's private directory.

## 2. How data is used

Local data is used only to provide features you can see: showing timetables and todos, sending reminders, syncing school courses, AI chat, importing/exporting backups, and personalization. The author does not use this data for advertising, profiling, or sale.

## 3. Third-party data flows

Except for the scenarios you explicitly trigger below, the app does not send your data to third parties. How third parties handle data is governed by their own policies and is outside the author's control.

### 3.1 School / academic systems

- When you log in, sync, or enable automatic sync, the app sends your account, password, cookies, or sync requests to the school domain you selected and receives courses, todos, and similar data.
- Requests go directly from your device to the school system; the author does not relay or record them.
- Confirm that your use complies with school rules and third-party terms.

### 3.2 Wakeup share-key import

- When you use share-key import, the key you provide is sent to `api.wakeup.fun`.
- The app downloads the adapter script `wake_up.js` from a public open-source repository and executes it in a headless WebView; the script is maintained by a third-party author.
- You bear the consequences of third-party services, script behavior, or terms.

### 3.3 Remote school catalog

- "Update school list" is manual-only and disabled by default; the app does not fetch it at startup or in the background.
- When updating, the app accesses the author-maintained Gitee repository (`classpp_schoolsync`), downloads JSON declarations, and caches them locally.
- Declarations contain no remote JavaScript, and the app hard-validates fields such as domains. The first time you connect to a remote school, the app shows its name and login domain for confirmation.

### 3.4 AI provider (BYOK)

- When you use AI chat, the app sends your chat content, images attached to messages, and automatically injected context (timetable, schedule, up to 30 unfinished todos, groups, tags) to the provider you choose.
- Requests go directly from your device to the provider (including custom endpoints); the author does not participate in, relay, or record them.
- Provider privacy policies, retention, content moderation, billing, and availability are determined by the provider. Read their policies before use, and do not send ID numbers, passwords, bank card numbers, health information, or other sensitive content.
- If the provider is located outside your jurisdiction, you are responsible for cross-border transfer risks and compliance.

## 4. System backup and manual export

- The app enables Android system backup (`allowBackup`) and configures cloud backup and device-transfer rules. Account passwords, cookies, API keys, and other sensitive files are excluded; timetables, todos, tags, background images, AI conversations, and conversation images may still be backed up to your cloud or transferred to a new device.
- The scope and behavior of system backup are controlled by the operating system, device manufacturer, and your account settings, which the author cannot control. If you do not want data in cloud backups, disable system backup or use a work profile/separate device.
- Export backups from "Data management" produce an **unencrypted ZIP** containing timetables, todos, tags, background images, AI conversation text, and similar data (excluding credentials, API keys, and AI conversation image attachments). Keep it safe and do not upload it to public cloud drives, chat apps, or other locations accessible to others.

## 5. Permissions

The app requests or uses the following system capabilities. Whether to grant them is your decision:

- **Network access**: access school systems, the Gitee remote school repository, Wakeup services, and your configured AI provider;
- **Notifications**: course/todo reminders and sync result notifications; some Android versions use promoted ongoing (Live Updates) notifications;
- **Calendar read/write**: read the calendar account list to choose a target and write todo events to the calendar you select;
- **Exact alarms and boot-completed**: reschedule reminder alarms and daily sync tasks;
- **`SET_ALARM`**: launch the system clock app to create a one-time alarm (a normal permission granted at install time);
- **Camera**: AI chat photo capture is handled by the system camera app; the app does not request the camera permission directly;
- **File picker / Photo Picker**: used to select images and import/export backups; no additional storage permission is requested.

Denying a permission may make the corresponding feature unavailable.

## 6. Retention and deletion

- Local data remains on your device until you delete it in the app, clear app data, or uninstall the app.
- The author does not operate a server, so there is no server-side copy to request deletion of.
- Backups you export manually, system cloud backups, and data already sent to school systems or AI providers must be managed or deleted by you in the relevant place.

## 7. Security

- The app uses the Android Keystore to encrypt credentials and API keys and stores sensitive data in the app's private directory. However, this project has **not been security-audited**, and absolute security cannot be guaranteed.
- Data may still be exposed if the device is rooted/jailbroken, unlocked, infected with malware, exploited, or if backup files are obtained by others. Manage your device and backups carefully.

## 8. Children

This project is not designed for children and does not intentionally collect children's personal information. Minors should use it under the guidance of a guardian, who is responsible for their use.

## 9. Policy updates

This policy may be updated as features change and takes effect when published. Material changes will be described in the app or the project repository.

## 10. Contact

If you have questions about this policy, contact the author via the project's GitHub Issues:

<https://github.com/ChengFY0710/Classpp/issues>

---

Copyright (c) 2026 ChengFY
