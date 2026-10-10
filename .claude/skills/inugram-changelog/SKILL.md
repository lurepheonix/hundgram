---
name: inugram-changelog
description: >
  Use when drafting release notes for the next inugram release - the
  `changelogs/hundgram/<build>.md` file the release workflow posts to Telegram and GitHub.
  Trigger on "write the changelog", "draft release notes", "what changed since the
  last release", or before dispatching `release.yml`.
---

# Inugram changelog

The release workflow reads `changelogs/hundgram/<build>.md`, where `<build>` is the build
number of the release being made. It puts the file inside the collapsed
"Changelog" block of the Telegram post, followed by an auto-generated
"Full changelog" link, and uses it as the GitHub release notes. Without the file,
the post only has that link.

`changelogs/inugram/` holds upstream Inugram's release notes as merged (reference
only, never edited). `changelogs/` root holds no loose `<N>.md` files: after every
upstream merge, `git mv` any new `changelogs/<N>.md` into `changelogs/inugram/`
before committing.

## Find the range

`INU_BUILD` is the source of truth for the next build number. Confirm with
`gh variable get INU_BUILD` (outside the sandbox) when available.

Tags share a namespace with upstream (`v<APP_VERSION_NAME>-<build>` on both sides),
so tag arithmetic is unreliable: `git describe --tags --match 'v*'` may resolve to
an upstream tag. Prefer the last Hundgram release tag (highest build among tags
reachable without crossing the upstream merge), and trust `INU_BUILD` if they differ.

```sh
git fetch --tags
```

## Gather

- `git log --no-merges --format='%h %s%n%b' $base..HEAD`
- `git diff $base..HEAD -- FEATURES.md`: the best source of user-facing wording.
- Newly arrived `changelogs/inugram/*.md` since the last Hundgram release: upstream
  features new to Hundgram users get full bullets (adapted, not copied), not a
  one-line rebase entry. A plain stock-Telegram version bump stays one bullet.
- `git diff $base..HEAD -- <path>` for commits whose subject is unclear.
- `git diff $base..HEAD -- patches/` shows stock changes; the patch name tells the area.

## Write

Only `##` sections with `-` bullets, in this order, omitting empty ones, with an
empty line between sections:

```md
## New
- Translator: plugins can register their own translation providers

## Improved
- ...

## Fixed
- ...

## Plugins
- `openChatHistory` api for plugin-driven chat screens
```

- One bullet per user-visible change; merge commits that touch the same feature.
- Describe what the user sees, not the code. Start with the area (screen, setting, feature) when it helps. 
- For new/updated settings, add deep links and paths where applicable (e.g. [Messages › Pinned reactions](tg://settings/inu/pinned-reactions))
- `## Plugins` is for plugin authors: api, grants, SDK, CLI. Keep the title exactly
  `Plugins`: pluginless builds hide the section by that name.
- Skip `infra:`, CI, refactors, tests, docs, and upstream rebases unless they change behavior. 
- A rebase onto a new Telegram version is one bullet in New, with a short breakdown of the changes (one sentence at most, only keep the most important).
- Short and plain. No em-dashes, no marketing tone, no trailing periods, no mannered prose.
- Avoid empty sections, and make sure to include empty lines between sections.
- No version heading or links to the full changelog; CI adds those.

Write the file, show it to the user, and let them edit. Do not commit it.
Remind the user to verify translations before releasing.