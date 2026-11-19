# script-android-app

`script-android-app` scripts one Android app on one device. It runs a manual-capture yaml or a regression flow. It does not start a second device, and it does not decide whether a result passed.

`capture-manual-screenshots` is the screenshot command. Two serials there start two copies of this tool. A regression flow is run here, not through that command.

```
./script-android-app --check
./script-android-app --serial SERIAL [--form phone|tablet] [--locales all|TAG,TAG] [--shots GROUP,FILE] [--replace]
./script-android-app --check --flow FILE
./script-android-app --serial SERIAL --flow FILE [--var k=v ...] [--run-dir DIR]
```

`--check` does not use adb. A flow and a manual-capture flag (`--form`, `--locales`, `--shots`, `--replace`) together are an error. One `--serial` is required for a device run. The tool never picks a device.

## Regression flow

Flows are JSON files, schema `capture-flow/1`, usually `regress/flows/*.json`. `regress/config.json` supplies `${pkg}`, `${activity}`, and any other keys the flow uses. `decoder` and `logcat_forbid` are ignored here. The wrapper owns install, clear, permissions, animator scale, push, pull, logcat, and pass or fail. Animator duration scale 0 for the run is that wrapper's default.

A step has one command: `tap`, `long_press`, `swipe`, `type`, `back`, `home`, `rotate`, `wait_for`, `wait_gone`, `wait`, `read_text`, `screenshot`, `note`, `launch`. A selector has one of `res_id`, `string`, `text`, or `desc`. `desc` may be a string id or `{ "literal": "..." }`. `when` is `{ "var": "name", "eq": "value" }` with `eq`, `ne`, `gt`, `lt`, `ge`, or `le`. A false `when` skips the step.

`tap`, `type`, `long_press`, `wait_for`, and `wait_gone` stop the run when the node is not there before `timeout_s`. The tool then saves a screenshot and a UI dump and exits 1. `optional: true` records the absence and continues. `read_text` and `screenshot` record what is there, including a missing node, and do not stop the run. `save_as` on `read_text` stores that text for a later step. `wait` is at most 2 seconds. There is no `compare`, no `on_fail`, and no arithmetic.

`--run-dir` defaults to `<sandbox>/regress-runs/<YYYYmmdd-HHMMSS>-<serial>/`. The report is `report.json` and `report.txt`. A later run with the same directory appends a run. The report has timings, values, artifact paths, and whether a step stopped. It has no pass or fail field. Nothing is written to `/tmp`.

Exit 0 means the steps were driven. Exit 1 means a step could not be driven. Exit 2 means the flow or the command was wrong.
