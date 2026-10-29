package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox

/**
 * Storage gate for imported / updated / edited profiles, aligned with
 * the startup pipeline (G4). The gate used to check only the
 * sanitize product, but sanitize is a SUBSET of the startup heal
 * (ConfigNormalize.apply): legacy inbound migration, special-outbound
 * migration, missing-reference retargeting, remote rule-set repair and
 * download-client repair all run at startup only. A config the
 * startup pipeline could have fixed was therefore rejected at the
 * door — and for subscription updates the rejection was permanent and
 * explained as a network failure.
 *
 * The gate now asks the same question startup asks: "can this be
 * normalised into something the kernel accepts?" The sanitized
 * content is checked first; if the kernel refuses it, the startup
 * heal runs and its product is checked; only when both fail is the
 * content rejected, with a readable reason (kernel wording included,
 * URLs stripped) instead of a raw Go error or a failure count.
 *
 * What gets STORED is still the sanitized content, unchanged: the
 * heal is deterministic and re-runs at every start, so accepting via
 * the heal does not change what is on disk.
 */
object ConfigGate {

    sealed interface Result {
        /** [healRescued] = the plain sanitize product failed the check
         *  and only the startup heal made it acceptable. */
        data class Accepted(val healRescued: Boolean) : Result

        data class Rejected(val reason: String) : Result
    }

    /** Production check adapter backed by the real kernel. */
    fun libboxCheck(candidate: String): String? = runCatching { Libbox.checkConfig(candidate) }
        .exceptionOrNull()?.let { it.message ?: it.toString() }

    /**
     * [check] returns null when the kernel accepts the candidate, or
     * the kernel's error text. Injected so unit tests do not need
     * libbox; production passes [libboxCheck].
     */
    fun validate(content: String, check: (String) -> String?): Result {
        val firstError = check(content) ?: return Result.Accepted(healRescued = false)
        val healed = runCatching { ConfigNormalize.heal(content) }.getOrNull()
        if (healed != null && healed.content != content && check(healed.content) == null) {
            return Result.Accepted(healRescued = true)
        }
        return Result.Rejected(readableReason(firstError))
    }

    /**
     * Turn a raw kernel check failure into a short, user-readable
     * rejection. Deliberately names the real cause (the content, not
     * the network): the update paths used to misreport exactly this
     * case as "direct connection failed / node cut the connection".
     */
    fun readableReason(checkError: String): String {
        val cleaned = checkError
            .replace(Regex("https?://[^\\s\"'<>]+"), "[链接已隐藏]")
            .trim()
        val brief = cleaned.lineSequence().firstOrNull().orEmpty().take(160)
        return if (brief.isEmpty()) {
            "这份配置有一处当前内核不认的写法，已拒绝，没有改成直连。"
        } else {
            "这份配置有一处当前内核不认的写法，已拒绝，没有改成直连。内核原话：$brief"
        }
    }
}
