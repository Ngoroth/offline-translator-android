# Security policy

This is a development repository with no supported production release series or
committed security response schedule.

## Private vulnerability reporting

Use GitHub's [private vulnerability reporting form](https://github.com/Ngoroth/offline-translator-android/security/advisories/new)
to send a report to the repository maintainers. Sign in to GitHub to use the form.
Private vulnerability reporting is enabled for this repository.

**Do not post vulnerability details, exploits, credentials, conversation text,
recordings or device logs in public issues, discussions or pull requests.**

If GitHub **Security → Report a vulnerability** is unavailable, a public issue may
ask only for a private security contact, without describing the vulnerability or
including reproduction steps or sensitive data. Withhold details until a private
channel is available and confirmed; a “security” label does not make an issue private.

## Privacy and diagnostic logs

Historical development builds exposed recognized/translated text in ML Kit native
logs. ML Kit is no longer included; removal does not fix those older builds or
prove that every retained component is free of content logging. Offline processing
does not guarantee safe diagnostic logs. Do not publish raw logcat, bugreports,
device dumps or real conversation recordings; use synthetic reproductions and
review/redact necessary excerpts locally.

Whisper and Tencent process locally after explicit model preparation. Optional
Android recognition and System speech output use installed services with their
own privacy terms. Android recognition's offline request relies on the selected
service honoring it; it is not independent no-network attestation. See the
[privacy policy](docs/privacy-policy.md) for those boundaries.
