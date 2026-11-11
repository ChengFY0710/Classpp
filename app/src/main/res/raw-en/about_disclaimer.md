**Last updated: 2026-11-11**

> Please read this Disclaimer in full before using this project. By downloading, installing, compiling, running, or otherwise using this project, you acknowledge that you have read, understood, and agreed to all of the terms below. If you do not agree, stop using the project immediately and delete it and all copies.

## 1. Nature of the project

- Class Plus Plus (Classpp / Class++, "this project") is a **non-commercial open-source project** started by an individual developer as a hobby. Its code is mostly generated and iterated by AI coding tools ("Vibe Coding"). It is **not a professional software engineering product** and has not undergone formal architecture review, code review, security audit, penetration testing, or a complete quality assurance process.
- Feature designs, UI copy, and documentation may also contain AI-generated content and may be inaccurate, incomplete, or wrong.
- The project is provided **"AS IS"** and **"AS AVAILABLE"**, without warranty of correctness, completeness, stability, security, or fitness for any particular purpose.

## 2. Security risks are yours to bear

- This project has **not undergone any security audit**. It may contain (and likely does contain) defects, logic flaws, improper permission handling, missing data validation, vulnerable third-party dependencies, and other security issues.
- The app includes networking, dynamic content delivery, and web execution capabilities: manually fetching remote school declarations, downloading and executing third-party Wakeup adapter scripts, logging into school LMS/academic systems in an embedded WebView and executing data-fetching scripts, and connecting directly to third-party AI services. These capabilities increase the attack surface and may cause unexpected behavior depending on third-party content, network conditions, or device state.
- **You accept all security risks and consequences of using this project. The author is not liable.**
- Do not use this project with sensitive data, in production environments, on other people's devices, or in any scenario whose consequences you cannot bear. If you use it for anything important, perform your own independent security assessment and hardening.
- Use a dedicated password for school systems and avoid logging in or importing over untrusted networks (such as public Wi-Fi). If you notice unusual logins, password changes, or account restrictions, handle them immediately.

## 3. Data, privacy, and backups

- Timetables, time schemes, todos, tags, schedule background images, AI conversation records and copied conversation images, app settings, and similar data are stored locally on your device by default.
- School account passwords and AI API keys saved by the app are encrypted with the Android Keystore (credential saving is supported by only some schools). Session cookies created by WebView login are stored by WebView in the app's private directory. Encryption reduces the risk of plaintext exposure, but **cannot guarantee** absolute security if the device is rooted/jailbroken, unlocked, infected with malware, exploited, or if backup files are obtained by others.
- The author does not operate a server that collects user data. Except for network features you explicitly trigger, the app does not upload your data to the author.
- The project enables Android system backup (`allowBackup`) and configures cloud backup and device-transfer rules. Credentials, cookies, API keys, and other sensitive files are excluded from backup; however, timetables, todos, tags, schedule backgrounds, AI conversations, and conversation images may still be synced to your cloud backup or transferred to a new device. The scope and behavior of system backup are controlled by the operating system, device manufacturer, and your account settings, which the author cannot control.
- Export backups from "Data management" produce an **unencrypted ZIP** containing timetables, todos, tags, background images, AI conversation text, and similar data (excluding credentials, API keys, and AI conversation image attachments). Keep it safe and do not upload it to public cloud drives, chat apps, or other locations accessible to others.
- Importing a backup is a **destructive restore** that overwrites the corresponding data on the current device. Corrupted, tampered, incompatible, or untrusted backups may cause data loss, errors, or unexpected behavior. Only import backups you created and trust.
- System backup/restore, device migration, clearing data, uninstalling the app, storage corruption, destructive restore, or software defects may cause data **loss, corruption, or disclosure**. **Back up important data regularly.**

## 4. Third-party services, network features, and imports

This is not a purely offline app. The following features rely on **third-party services, APIs, scripts, or content**. The author has **no affiliation, partnership, or authorization relationship** with these third parties and cannot control their availability, security, data accuracy, or terms.

### 4.1 School LMS / academic system sync and web login

- The app supports syncing with the course centers/academic systems of multiple schools and allows direct login to school systems in an embedded WebView. Login and data requests are sent directly from your device to the school's domain. The app only stores session cookies locally and, when you enable "save credentials / auto-renew" (supported by some schools), stores your account and password locally in encrypted form.
- Automatic sync, daily sync, and related notifications access the school system periodically according to your settings. Sync results may contain **duplicates, omissions, delays, or field/time parsing errors**. Always refer to what the school system displays.
- These features may require providing your account, password, cookies, or other credentials to the school system. **You are responsible for confirming that this complies with school rules, third-party terms, and your own risk tolerance.** The author is not liable for account restrictions, bans, password leaks, school system changes, or data errors.
- The author does not collect or upload your credentials; however, the author cannot control how school systems, network operators, or your device environment handle data.

### 4.2 Wakeup share-key import

- Share-key import relies on **Wakeup and related services**. When you use it, the key you provide is sent to `api.wakeup.fun`, and the app downloads an adapter script (`wake_up.js`) from a public open-source repository and executes it in a headless WebView.
- The script is maintained by a third-party author. The author cannot guarantee its content, security, or continued availability. Executing third-party scripts carries risks such as access to or modification of page data, transmission of data to third parties, and breakage caused by script updates.
- The author is not liable for any consequences of Wakeup service changes, bans, data errors, API failures, script behavior, or terms disputes. Confirm that your use complies with third-party terms and school rules.

### 4.3 Remote school catalog (declarative adapters)

- "Update school list" on the school picker is a **manual-only** action. The capability is disabled by default; the app does not fetch it at startup or in the background. After a successful update, declarations are cached locally and cold starts only read the local cache.
- The remote list and school declarations come from a Gitee repository maintained by the author (`classpp_schoolsync`). Declarations are JSON data containing login domains, data paths, field mappings, and UI copy; they **do not contain remote JavaScript**. The app hard-validates declarations and shows the school name and login domain for confirmation the first time you connect to a remote school.
- Declarations may still be outdated, wrong, modified by third-party mirrors, or inconsistent with the actual school system. The author is not liable for sync failures, data errors, or other consequences caused by remote declarations, third-party repositories, network conditions, or school system changes.

### 4.4 AI chat (BYOK, bring your own key)

- AI chat requires your own provider API key, endpoint, and model. The app sends your chat content, images attached to messages, and **automatically injected context** (timetable, schedule, up to 30 unfinished todos, groups, tags) to the provider you choose. Context may contain course, teacher, location, and personal schedule information.
- Requests go **directly** from your device to the provider you choose (including custom endpoints). The author does not participate in, relay, or record these requests. Provider privacy policies, retention, content moderation, billing, rate limits, and availability are determined by the provider; providers may log, analyze, or even use data for model training. Read their policies before use.
- **Do not send ID numbers, passwords, bank card numbers, health information, or other sensitive content to AI.** If the provider is located outside your jurisdiction, you are responsible for cross-border transfer risks and compliance.
- AI output may be **wrong, outdated, incomplete, or misleading** and does not represent the author's views. Todo drafts parsed from AI replies are written directly to the todo store without extra confirmation; always verify them. Do not rely on AI output for medical, legal, financial, or other professional decisions.
- You are responsible for safeguarding your API key, controlling usage, and paying fees. The author is not liable for losses caused by key leaks, unauthorized charges, provider bans, or misconfiguration; you bear the risks of custom endpoints or unofficial proxies.

### 4.5 Other third-party dependencies

- The project depends on open-source components (see the project README acknowledgements), each under its own license. The author is not liable for consequences of third-party service changes, bans, data errors, API failures, or terms disputes.
- Third-party names and marks remain the property of their respective owners.

## 5. Unofficial status

This project is an **unofficial, non-profit** third-party client. It has no affiliation or partnership with any school, educational institution, LMS/academic system provider, Wakeup, AI provider, Gitee, or other third party. It does not represent the positions of any of these organizations. Third-party names and marks remain the property of their respective owners.

## 6. Permissions

The app requests or uses the following system capabilities. Whether to grant them is your decision:

- **Network access**: access school systems, the Gitee remote school repository, Wakeup services, and your configured AI provider;
- **Notifications**: course/todo reminders and sync result notifications; some Android versions use promoted ongoing (Live Updates) notifications;
- **Calendar read/write**: read the calendar account list to choose a target and write todo events to the calendar you select;
- **Exact alarms and boot-completed**: reschedule reminder alarms and daily sync tasks;
- **`SET_ALARM`**: launch the system clock app to create a one-time alarm (a normal permission granted at install time, with no runtime prompt); actual behavior depends on the device's clock app;
- **Camera**: AI chat photo capture is handled by the system camera app; the app does not request the camera permission directly. Captured images are imported as conversation attachments and may be sent to the AI provider;
- **File picker / Photo Picker**: used to select images and import/export backups; no additional storage permission is requested.

You bear the consequences of privacy disclosure, mistimed reminders, modified calendar data, or photos sent to third parties caused by granting these permissions. Denying a permission may make the corresponding feature unavailable.

## 7. Limitation of liability

To the maximum extent permitted by applicable law:

- The author is not liable for any direct, indirect, incidental, special, punitive, or consequential damages arising from the use or inability to use this project, including but not limited to data loss, device damage, account bans, academic or work losses, lost profits, losses caused by AI output or third-party data, and third-party claims;
- Whether the claim is based on contract, tort, strict liability, or any other legal theory, and whether or not the author was advised of the possibility of such damages;
- This disclaimer does not exclude or limit liability that cannot be excluded or limited by law (for example, personal injury caused by intent or gross negligence). If part of these exclusions is not permitted in your jurisdiction, those parts do not apply there, and the remaining terms remain in effect.

## 8. Open source, modifications, and derivative works

- This project is released under the **PolyForm Noncommercial License 1.0.0** and may be used for non-commercial purposes only.
- Anyone who modifies, further develops, packages, distributes, or creates derivative works based on this project is responsible for their own actions. **Any problems (including security issues) caused by third-party modified versions, derivatives, or unofficial builds are unrelated to the original author, who accepts no liability.**
- The author's remote school declaration repository is also provided "as is"; third-party mirrors, modified declarations, or unofficially distributed declarations are unrelated to the author.
- The author has no obligation to provide technical support, security updates, or bug fixes, or to respond to any issue or pull request.

## 9. Miscellaneous

- This disclaimer may be updated at any time and takes effect when published, without separate notice.
- If any provision is held invalid or unenforceable, the remaining provisions remain in effect.
- This disclaimer is interpreted and applied to the maximum extent permitted by applicable law.

---

Full disclaimer and latest version: <https://github.com/ChengFY0710/Classpp/blob/main/DISCLAIMER.md>

Copyright (c) 2026 ChengFY
