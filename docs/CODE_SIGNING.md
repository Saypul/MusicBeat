# Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io/), certificate by [SignPath Foundation](https://signpath.org/).

## What is signed

The Windows packages of the MusicBeat desktop app, as published on this repository's [Releases](https://github.com/Saypul/MusicBeat/releases) page:

- `MusicBeat-<version>-windows-x64-setup.exe`
- `MusicBeat-<version>-windows-x64.msi`
- `MusicBeat.exe` inside `MusicBeat-<version>-windows-x64-portable.zip`

They are built from this repository's source by the [`Desktop · Windows`](../.github/workflows/desktop-windows.yml) GitHub Actions workflow and submitted to SignPath from that workflow; nothing built on a personal machine is signed. The Android APKs are signed separately with the project's own Android key and are not part of this policy.

## Team roles

| Role | Members |
|---|---|
| Committers and reviewers | [Saypul](https://github.com/Saypul) |
| Approvers | [Saypul](https://github.com/Saypul) |

Every release signing request is approved by hand by an approver.

MusicBeat is a fork of [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh, licensed under GPL-3.0. Upstream code is reviewed by a committer before it is merged here.

## Privacy policy

MusicBeat does not collect telemetry, analytics or crash reports, and runs no server of its own. It connects only to services the user chooses to use or that playback needs:

- **YouTube Music** (music.youtube.com, youtube.com and Google sign-in), to browse, search and stream.
- **Lyrics and artwork providers**, while lyrics or animated artwork are shown.
- **GitHub** (api.github.com), to check this repository for a newer release.
- **Optional services**, only once the user enables them: Last.fm, ListenBrainz, Spotify, Discord, Internet Archive, JioSaavn, a Listen Together server, and addon or module servers the user adds.

Sign-in sessions and tokens are stored only on the user's own device.
