# Developer notes

## Data transfer

Restore has no content checks. Path confinement is the only tar check. It can fix or break the app data on purpose.

Official authority is `com.davidlang.torque.fuelsmoothing.datatransfer`. A local build's authority is `<applicationId>.datatransfer`.

The serial comes from `timeout 10 adb devices -l` (Pixel_6_Pro). Commands use `timeout` and `exec-out` / `exec-in`. Never `adb shell content read > file`. Never start, restart, or kill the adb server. Never clear logcat. Ask David before installing.

Export:

    timeout 120 adb -s <serial> exec-out content read --uri content://<applicationId>.datatransfer/export > backup.tar

Import:

    timeout 300 adb -s <serial> exec-in content write --uri content://<applicationId>.datatransfer/import < backup.tar

Import status:

    timeout 30 adb -s <serial> exec-out content read --uri content://<applicationId>.datatransfer/import-status

Status file: `context.noBackupFilesDir/datatransfer/last-import.json`, read through `/import-status`.

## Migration

Keep a backup (the 20261016-1913 `plugin-data.tar` or an export). Uninstall a build made with `-Pfuelsmoothing.official=true` or `FUELSMOOTHING_OFFICIAL=1` before the Play package `com.davidlang.torque.fuelsmoothing`, because the signature differs. A `com.<user>.torque.fuelsmoothing` install is a different package. Install, import, then read `/import-status`.

## Eval

Run from the repo root:

    ./gradlew :app:testDebugUnitTest --tests com.davidlang.torque.fuelsmoothing.EndpointEvalTest -Dfuelsmoothing.evalDir="$PWD/sandbox/research/pixel6-full-20261016-1913/unpacked/files"

The report is `<repo root>/sandbox/research/endpoint-eval-<YYYYMMDD-HHMM>.md`. Defaults stay until David picks `ENDPOINT`, `INPUT`, and `FIT_WEAK`. A drive in that table is one connect-to-disconnect capture. A fill is a hard break inside it. Pieces are the smaller spans.

## Piece constants

`ANALYSIS_VERSION` bumps when an analysis constant or method changes. The value after these plans is 4.

- `MAX_SPAN = 1.0` gauge points
- `END_MIN_READINGS = 16`
- `END_WIN_S = 60`
- `V_MIN_KMH = 30`
- `FIT_WEAK = true`
- `ENDPOINT = PIECE_FIT`
- `Kalman.INPUT = BLOCK_MEDIANS`
