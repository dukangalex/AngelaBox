# Distinct Qwen candidate results

Two materially different paths were investigated without personal credentials, account creation, payment, production modification or release.

## Official hosted Qwen3-235B public demo

Prospective plan 0324588. Source: https://huggingface.co/spaces/Qwen/Qwen3-Demo . The browser frontend remained Loading after a fresh session. Its public advertised Gradio API was callable: one synthetic case was submitted via gradio_client 1.13.1, model label qwen3-235b-a22b, thinking toggle disabled. The add_message call returned upstream Gradio AppError after 2.8 seconds without a visible model answer. The cause is unverified; this is an availability failure, not a scored model-quality failure. No repeat or undocumented upstream access was used. The public demo has no established production SLA/API entitlement.

Exact input, visible error, and advertised parameter contract are in evidence/qwen-official. This route can be reconsidered if that official anonymous demo actually becomes usable, not merely because its page exists.

## Local official Qwen3-1.7B Q4_K_M

Prospective plan a497e83. Official public model https://huggingface.co/Qwen/Qwen3-1.7B-GGUF pinned at cd3d34a469f89b12676edce7d272750201959466. Model bytes were SHA-256 checked against official LFS oid. Official llama.cpp b7633 archive was checked against release asset digest. Provenance receipts and reproducible script are included; model/runtime downloads themselves are not committed.

The local runtime bound only to 127.0.0.1 and shut down afterward. One frozen healthy fixture completed in 43.83 seconds, 447 prompt / 314 generated tokens, about 9.11 generated tokens/second on two CPU threads. No test content was sent to a model provider; only public weights/runtime were downloaded. This establishes local CPU execution on the current Computer, not Android performance or a production feature.

The output was invalid JSON and invented that the independently measured loopback transactions were virtual-device simulations with no actual network connection. Its follow-up drifted into actual-network testing and broad network-health claims. No repair was attempted. Under the preregistered early-stop rule the other seven cases were not run; the full eight-case gate did not pass. See evidence/qwen-local/{request,result,assessment}.json. This one-case assistant review is not a general accuracy comparison or evidence that every local model fails.

## Outcome and recovery conditions

Keep the original AI-diagnosis objective and release gates. There is no currently demonstrated replacement that passes diagnostic quality. The known official 8B Q4 model requires about 5.03GB of weights before KV/runtime overhead, exceeding this Computer's 3.8GiB RAM/no-swap budget; we did not try to force it or claim it would pass. The smaller tested model can run but fails the first mandatory case. Higher-capacity candidates, other quantizations and other hosts remain untested.

A worthwhile next experiment needs a concrete stronger model that fits available zero-extra-cost compute, or a restored official no-account hosted route. Reuse the same frozen evidence/baseline and independent holdout/adversarial tests; do not start another equivalent prompt-rewrite loop. Larger local-model evaluation becomes recoverable with existing adequately provisioned no-cost compute if made available, not a request to purchase resources or supply personal keys. Production integration additionally requires successful App consent/result display and semantic quality verification. No inference about all model paths is supported.
