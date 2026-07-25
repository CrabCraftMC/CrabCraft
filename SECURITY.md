# Security policy

## Reporting a vulnerability

Please use the repository's **Security** tab to open a private vulnerability report. Do not disclose a suspected vulnerability in a public issue, discussion, pull request, Discord channel, or Minecraft chat.

Include the affected component and revision, reproduction steps, expected impact, and any suggested mitigation. Remove live credentials, personal data, and destructive proof-of-concept payloads from the report.

Maintainers should acknowledge a complete report within seven days and will coordinate validation, remediation, and disclosure with the reporter. Timelines depend on severity and operational risk.

## Supported versions

Security fixes target the current `main` branch and the latest published release. Older releases are not guaranteed to receive backports.

## Secrets

If a credential appears in an issue, commit, build log, or release asset, revoke it immediately before attempting repository cleanup. Deleting the visible text does not revoke a credential or remove it from Git history.
