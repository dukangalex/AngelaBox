#!/usr/bin/env python3
"""Guard ConfigAdBlock overlay wiring and JSON shape."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def main() -> int:
    errors: list[str] = []
    block = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigAdBlock.kt")
    if 'RULESET_TAG = "geosite-category-ads-all"' not in block:
        errors.append("ad block must use geosite-category-ads-all")
    if "testingcf.jsdelivr.net" not in block:
        errors.append("ad block rule-set URL must use testingcf jsDelivr")
    if 'put("action", "reject")' not in block:
        errors.append("ad block must reject matching traffic")
    if "alreadyRoutesAds" not in block or "ruleSetMentioned" not in block:
        errors.append("ad block must not stomp a script that already routes ads")
    if "ensureRuleSet" not in block:
        errors.append("ad block must inject a remote rule-set when missing")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "ConfigAdBlock.apply" not in override:
        errors.append("runtime overlay must apply ConfigAdBlock")
    if 'applyOne(warnings, "广告拦截")' not in override:
        errors.append("ad block must apply in isolation")
    webrtc = override.find('applyOne(warnings, "防 WebRTC 泄露")')
    ads = override.find('applyOne(warnings, "广告拦截")')
    if webrtc < 0 or ads < 0 or ads < webrtc:
        errors.append("ad block must apply after WebRTC so reject rules stay in front")

    settings = read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt")
    if "adsBlock" not in settings:
        errors.append("Settings.adsBlock missing")
    keys = read("app/src/main/java/io/nekohasekai/sfa/constant/SettingsKey.kt")
    if "ADS_BLOCK" not in keys:
        errors.append("SettingsKey.ADS_BLOCK missing")
    ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "广告拦截" not in ui:
        errors.append("profile override UI must expose 广告拦截")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("adblock overlay ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
