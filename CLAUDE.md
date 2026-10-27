# FoldDesk – notes for AI-assisted sessions

DeX-style launcher for the Galaxy Z Fold7 inner screen. Read `README.md`,
`docs/ARCHITECTURE.md` and `docs/DEVELOPMENT.md` first.

- **Show a mockup before implementing any visual change** (HTML in `design/`,
  rendered to PNG) and wait for approval. Behaviour-only fixes can go straight
  to code.
- **This repository is public.** Never commit credentials, device serials, IP
  addresses, real names, locations, or captures from the user's phone
  (wallpaper, notifications, app lists). Use placeholder data in mockups.
- Test on the real device over adb and verify with screenshots; mind the
  gotchas in `docs/DEVELOPMENT.md` (uiautomator suspends the accessibility
  service; the service may need re-enabling after an install).
- Match the existing code style: Compose, small composables, short "why"
  comments, theme colours from `Dex`/`LocalDexColors` (never hard-coded).
