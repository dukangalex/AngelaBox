# Evidence-referenced hypotheses experiment — NOT a shipped diagnostic feature

This branch evaluates a narrowly bounded diagnostic aid. It is deliberately outside app source sets, does not replace PR #40, and has no App UI or network call site.

## What it can show

The local catalogue maps allowed observations to provisional hypotheses, explicit evidence references, limitations, optional user-controlled checks and what each check can distinguish. A model selects up to three catalogue entries. The renderer rejects fabricated/duplicate IDs, wrong/missing evidence, extra fields and arbitrary prose. It never displays model-authored advice or executes changes. All observations remain visible, including abnormalities omitted by the model. No candidate is treated as proof of health or of a root cause.

The request is rebuilt from only id/state/code allowlists, rather than forwarding extra payload fields. Neither logs, URLs, configuration contents nor credentials are included in the synthetic cases. Production transport, the provider response envelope and the App's explicit consent are not tested by this standalone interpreter.

## Diagnostic utility finding

The displayed hypotheses are entirely locally authored. The model adds selection, not new evidence or causal identification. For example, `TUNNEL:ATTENTION:timeout` cannot distinguish a slow target, slow path or proxy malfunction; the proposed same-condition rerun distinguishes transient from repeated failure only. A FakeIP response is not sufficient DNS-fault evidence. An aggregated subscription warning cannot establish that the selected profile is affected.

Consequently, successful structured responses establish format compatibility and a bounded display surface, not improved diagnostic accuracy. The same useful hypotheses and checks can be displayed deterministically without a model. Do not market this prototype as full AI diagnosis or use it to pass the original release gate. Further diagnostic value would require independently useful, bounded observations and differential checks, followed by counterfactual/adversarial tests and final App acceptance. Any narrower product substitute must be described honestly and decided separately.

## Validation

18 isolated Kotlin/JUnit tests pass using Kotlin 2.2.21, JUnit 4.13.2, and `org.json:json:20240303` (the JSON dependency version in the project's app unit tests). Tested source: `src/InspectionHypotheses.kt`, `test/InspectionHypothesesTest.kt`, plus the existing `InspectionPolicy.kt` at base commit 491be5c. Tests cover evidence binding, DNS/FakeIP ambiguity, incomplete observations, aggregated subscription limits, omitted candidates, duplicate IDs/keys, extra advice/tool fields, trailing prose, and request allowlisting of unknown input fields.

Raw outputs from four sequential synthetic-only provider requests, bounded to a small catalogue, are saved in `evidence/`. They are a tiny convenience sample, not a quality estimate, reliability guarantee, SLA, or production acceptance. No App/UI/provider consent flow was exercised by those requests. One response per scenario cannot establish generalization or stability.

## Separate production status

PR #40 remains at 491be5c (60s read timeout, 90s disconnect ceiling, no automatic retries). Exact production CI run 41275877539 completed successfully for kernel tests, overlay guards and Android compile. Android synthetic consent acceptance run 41275900770 uses separate synthetic fixture commit 6aaa91f and must be assessed independently. Prior run 41273171344 timed out; subsequent run 41274871498 received HTTP 200 but its response failed parsing/content checks. These are different failures; neither is repaired merely by a green CI.


## Final Android synthetic fixture result

Run 41275900770 completed with failure at synthetic fixture commit 6aaa91f, based on production 491be5c. The final fixture commit has no `app/src/main` delta; its build script intentionally injects synthetic test configuration/evidence and stage markers at build time, so it is not an untouched production App test. The exact fixture artifact records cancellation with zero attempts and Send with one attempt; logs record HTTP 200 and a 2145-byte response followed by `AI_PARSE_REJECT=IllegalArgumentException`. It establishes transport success for this single request and a rejected response, not a successful interpretation or a general transport reliability claim. The stage marker does not identify which parse/content assertion rejected it; no raw response body was logged in the App test.

The before-send screenshot was inspected and discloses provider, model, evidence and explicit Cancel/Send controls. The final screenshot shows the local report only, without a successful AI interpretation. UI-tree/script output reports `AI analysis failed`; that transient text is not visible in the final screenshot. We do not infer semantic correctness, exact rejection reason, or final App acceptance from HTTP 200 or the consent counters. Relevant machine-readable receipts are in `evidence/android-*.json` and `evidence/android-synthetic-request-diagnostics.txt`.

No workflow is still waiting for assessment. Exact production CI is green, Android end-to-end interpretation remains failed, and the experiment adds constrained hypothesis presentation but has not demonstrated model-added diagnostic value. PR #40 stays open at 491be5c; no merge or release was performed.
