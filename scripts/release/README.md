# Frozen release build

Run on mbp (Android Studio JBR, SDK/build-tools 35.0.1 and SSH to tardis):

```sh
python3 scripts/release/build_release.py <full-main-commit> --dry-run
# Final delivery, only after the lead supplies the final main hash:
python3 scripts/release/build_release.py <full-main-commit>
```

`--dry-run` really builds and verifies the APK, but marks `build.json` as
`delivery_status: non-final` and delivers under `c0.dev.web/android-release/dry-run/<hash>/`.
Final delivery uses `c0.dev.web/android-release/<hash>/atoll-<version>.apk` and `build.json`.
The script checks main ancestry, fetches `git archive` over SSH, builds a fresh
frozen directory, reads version/min SDK from the APK, verifies its signer matches
mbp's debug key and checks the copied APK's SHA-256 before publishing build.json.
Logs and frozen source stay under `~/atoll-build/release/` on mbp.
It never installs an APK, starts an emulator or contacts an Atoll node.

Metadata parser self-check: `python3 -m unittest discover -s scripts/release -v`.
