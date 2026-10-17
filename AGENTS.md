# Agent entry point

- Read [README.md](README.md) for implemented behavior, device verification, build commands, limitations, and the next product work. Read [CHANGELOG.md](CHANGELOG.md) for delivered changes.
- The first torrent-playback milestone is complete, including owner-confirmed sound. Internet Archive is the first implemented search catalog (issue #7); check its PR status before continuing. Further catalogs require availability/API/terms research; full review of the current Internet Archive terms remains a release prerequisite because the terms body failed to load during research.
- Before interacting with the projector, read [.agents/skills/projector-adb/SKILL.md](.agents/skills/projector-adb/SKILL.md). It covers connection, remote keys, screenshots, logs, and safe session cleanup. Device addresses and captures belong in ignored `.local/`, not in commits.
- Follow the README's existing English documentation/comment and branch/commit/PR workflow. Check current GitHub issues and PRs before continuing; do not treat an archived session as current repository state.
