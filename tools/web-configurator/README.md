# Web configurator (not shipped)

Vite + React scratch UI (node converter / subscription parser / script auditor)
generated from the Google AI Studio template. It is **not** part of the Android
client and is not built by any workflow; it lives here so the repository root
stays a Kotlin/Android tree.

`npm install && npm run dev` from this directory if you want to poke at it.
Never put a real key in `.env.example` — the placeholder `GEMINI_API_KEY` is
leftover template boilerplate; nothing in the Android build reads it.

Run `npm run test:security` (or `node --test fetch-policy.test.mjs`) for the
standalone URL-address and user-agent input-policy regressions. These require no
installed packages or network and also run in repository CI. They do not test a
live deployment, DNS/connection behavior, or the full Express application.
