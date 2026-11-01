# Protocol v2 result — not accepted

## Outcome
The preregistered local-baseline comparison did not establish a safe, useful model-generated diagnosis protocol. No production integration, merge, release, filter relaxation or autonomous checks were performed.

- Android run 41276775490 completed with failure. Its injected synthetic test confirms cancel=zero attempts, send=one attempt; HTTP200/2145 bytes was rejected with fixed reason `SENSITIVE_ADVICE`. This does not reconstruct older unsaved response bodies. The screenshot shows the local report, not successful interpretation.
- The first eight-case provider request returned HTTP400 `output_token_limit_exceeded`, no scored answer. The pre-output amendment selected four cases for one bounded recovery call; HTTP200 after 26.55 seconds returned invalid JSON (extra trailing `}`), omitted the unmeasured-control case, and did not meet the preregistered semantic criteria. Application must reject the whole response; forensic prefix inspection is not repaired acceptance.
- The three visible cases copied cited field values accurately, but that is insufficient: broad health inference, unsupported cause implication, and a TLS/certificate-change suggestion remained. Assistant qualitative assessment found zero of four cases demonstrated an incremental distinction beyond the fixed local baseline. This is not blinded/independent scoring or a general model accuracy estimate.
- Independent local TLS experiment: two fixtures have the same initial TCP/TLS success plus 150ms header timeout. A bounded 1s wait receives headers for a 0.4s delay, still times out for a 1.4s delay. It distinguishes bounded response behaviour, not unique cause or permanently absent service. Model text was not executed.

## Saved reproduction
`PROTOCOL_V2_PLAN.md` was committed before calls; request records include plan commit. `evidence/v2/` preserves failed eight-case call and local controlled follow-up. `evidence/v2b/` preserves subset request, raw visible answer, and offline assessment. Run `python3 score_v2b.py` to regenerate the assessment. `controlled_followup.py` independently runs loopback-only TLS controls. No real user network/logs or credentials are inputs.

## Remaining work
PR #40 remains separate at 491be5c. Passing transport CI is not diagnostic acceptance. More prompting/blacklist relaxation or substituting prewritten explanations does not resolve these results. A production candidate needs an empirically capable protocol/model, enforced evidence and action boundaries, held-out/adversarial comparisons against the local baseline, then real-provider successful App display and consent acceptance on final code. No remaining CI wait from this experiment exists. Preserve the original goal; if no suitable model path is available within current account/privacy/cost constraints, report that limitation rather than declare the narrower local baseline to be completed AI diagnosis.
