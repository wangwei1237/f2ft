# AGENTS.md

Guidance for coding agents working in this repository.

f2ft is a face-to-face voice translation Android app (Mandarin ↔ English on one shared phone) built on the Google Gemini Live API. Product behavior is defined in `requirements/` directory.

## Required reading

- [`docs/development-process.md`](docs/development-process.md): mandatory workflow for every development request (new feature, change, or bug fix). Requirements document first, explicit user confirmation before coding, screenshot-backed test report after, then update `README.md`. Questions, code explanations, and read-only investigation are exempt.
- [`docs/architecture.md`](docs/architecture.md): code structure, runtime design, Gemini Live API constraints, coding conventions, build and on-device verification. Read the relevant sections before changing code, and update the document when the architecture or constraints change.

## Hard rules

- Never put API keys in code, resources, logs, screenshots, or commits. The key is entered on the settings page and stored encrypted on the device.
- Conversation records and audio stay in memory only. Do not add on-device persistence of recordings or transcripts.
- The GitHub repo (https://github.com/wangwei1237/f2ft) is public. Scan staged files for secrets before committing, never force-push `main`, and ask before pushing.
- Keep this file short. Put detailed rules in `docs/` and link them here.
