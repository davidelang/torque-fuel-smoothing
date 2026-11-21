# script-android-app

`script-android-app` scripts one Android app on one device. It runs a manual-capture yaml or a regression flow. Those are two command sets in one program. A yaml step is not a flow step, and a flow step is not a yaml step. The tool never picks a device, and a flow run does not decide whether a result passed.

`capture-manual-screenshots` is the screenshot command. One serial is one copy of `script-android-app`. Two serials are two copies at the same time. A regression flow is run on `script-android-app` directly. Each app that wants a pass or fail writes its own wrapper around the flow run. There is no shared regression library in this directory.

Deploy copies `script-android-app`, `capture-manual-screenshots`, and this manual into an Android project root, the same way it copies `build_app`. A repo whose build type is `none`, `command`, or anything other than `android-gradle` does not receive them. Do not put an app's yaml, flows, or fixtures in `build-examples/android-gradle/`. Deploy would install those files into every Android app.

Deploy adds and updates files that are still in that directory. It does not delete a file that has left it. A host that already has `capture-manual-screenshots.md` keeps that copy until someone removes it. This file is the manual.

David runs a capture. David publishes the scripts. An agent writes the yaml or the flow and may run `--check`. `--check` does not touch adb.

The device is already seeded for a screenshot walk. The yaml path does not create vehicles, accounts, or rows. It does not delete pictures. It may push a file named by `push_if_absent` only when that path is absent. A flow run does not install, clear, grant, push, or pull. The wrapper does that.

Do not invent screenshot files. A missing JPEG stays missing until a capture writes it. Store a locale image only when that language was photographed. A flow screenshot is a raw PNG of the screen at that step. The tool does not compare pixels and does not treat a missing label as a failed test.

Nothing is written to `/tmp`.

## Commands

From the app root, after the scripts are installed. Before they are installed, point `--root` at the app and run the copy under `build-examples/android-gradle/`.

```
./script-android-app --check
./script-android-app --serial SERIAL [--form phone|tablet] [--locales all|TAG,TAG] [--shots GROUP,FILE] [--replace]
./script-android-app --check --flow FILE [--flow FILE ...] [--var k=v ...]
./script-android-app --serial SERIAL --flow FILE [--flow FILE ...] [--var k=v ...] [--run-dir DIR]
```

```
./capture-manual-screenshots --check
./capture-manual-screenshots --serial SERIAL [--form phone|tablet] [--locales all|TAG,TAG] [--shots GROUP,FILE] [--replace]
./capture-manual-screenshots --serial SERIAL --serial SERIAL [--locales all|TAG,TAG] [--shots GROUP,FILE] [--replace]
```

`--config` defaults to `manual-capture.yaml`. `--root` defaults to `.`. A relative `--config` is joined under `--root`. An absolute `--config` is used as given. A flow run does not read the yaml. `--config` is ignored when `--flow` is set.

`script-android-app` takes one `--serial` for a device run. Two serials are an error, and the message points at `./capture-manual-screenshots`. One serial and no `--form`: a physical diagonal under `phone_max_inches` (default 8) is a phone. Anything else is a tablet.

Two serials on `capture-manual-screenshots` and no `--form`: the larger diagonal is the tablet, and each device is its own `script-android-app` process. A gap under `gap_inches` (default 1.5) exits before either copy starts. `--form` with two serials is an error. The two copies run at the same time. One failure does not stop the other. The wrapper exits 1 if either copy exits non-zero. Duplicate serials are an error.

`--locales` is `all` or a comma list of pref tags. Omitted means `en` for one serial and `all` for two. `--shots` is a comma list of group names and filenames. Omitted means every shot. An unknown token exits before any shot. There is no exact pixel-size check.

Without `--replace`, a selected JPEG of at least 1000 bytes is kept, and a locale whose selected shots are all that size is not opened and its language is not changed. `--replace` overwrites the selection.

A flow and a manual-capture flag (`--form`, `--locales`, `--shots`, `--replace`) together are an error.

Notes go to `<sandbox>/manual-capture-notes-<form>.txt`. The sandbox is `sandbox_dir` from `project.config`, otherwise `sandbox_path`, otherwise `sandbox/` under the app root. A flat JPEG the yaml path pushes is created in that same directory and removed after the push.

JPEGs are max width 1080, quality 75, metadata stripped. Do not commit a full-resolution capture. Flow screenshots are PNGs under the run directory, not this JPEG pipeline.

Needs `python3`, PyYAML, and Pillow. No third-party translator. No `run-as`. The yaml walk does not force-stop the app. `language.via: android` force-stops the package only after it changes that app's Android locale.

## Hooks

A hook exists for a gesture the yaml steps cannot say. The yaml names it. The app has a Python file of functions. VehicleExpenses has no hook, and a new app should start with none.

Every hook is an audit item. Read the function and decide which of these it is:

1. Missing primitive. The gesture will show up in another app. Add the step to the shared script, then delete the hook.
2. Lazy config. The walk can already be said with the steps in this manual. Rewrite it as steps.
3. A real exception. One app, one gesture, and the primitive list would get worse if this were added. Leave the hook, and say in a comment at the function why it is an exception.

Do not add a hook to avoid writing steps. Do not add an empty hooks file. A hook that returns `False` fails that step, so `on_fail` applies. `None` and `True` succeed. Raising `StopRun` ends that device's walk.

```python
HOOKS = {"name": name}

def name(device):
    return True
```

`device` can resolve a string id (`device.s`), dump the UI, tap, swipe, press Back, and record a note. It must not delete files, force-stop the app, or tap a `never_tap` label.

```yaml
hooks: scripts/manual-capture-hooks.py
# ...
- hook: name
```

`--check` loads that file and rejects a step whose name is not in `HOOKS`. A regression flow has no hook file.

## Yaml config

```yaml
package: com.example.app
activity: com.example.app/.MainActivity
forms: {phone_max_inches: 8, gap_inches: 1.5}
ignore_text: ["1x", "2x", "4x"]
never_tap: [save_id, sync_now_id]
never_tap_prefix: ["Sync now"]
output:
  en: docs/user-manual/images/{form}
  other: docs/i18n/{segment}/images/{form}
endonym_key: "lang_name_{pref}"
endonym_row: {}
drawer:
  open: nav_menu
  labels: [nav_settings, nav_help]
  open_if_text: [nav_settings]
  unblock_when_same: []
language:
  via: app
  menu: nav_menu
  settings: nav_settings
  field: settings_language
  system: settings_language_system
  ready: nav_menu
launch:
  sleep: 2.5
  dismiss: []
locales:
  - {pref: en, values: values, segment: ""}
sections: []
```

`strings_res` defaults to `app/src/main/res`. English is `values/strings.xml`. For any other pref, `values` is the resource directory (`values-in`, `values-b+en+001`). When that `strings.xml` exists, it overlays English. When it does not, `--check` warns and the labels fall back to English. `--check` still requires every id the walk uses to exist in English.

`never_tap` is a list of string ids the walk must not tap (Save, Sync now, delete, clear, a background switch). `never_tap_prefix` is an English literal prefix for a label that grows (`Sync now …`). `ignore_text` drops zoom chips when the script picks a control between two anchors. `never_tap` applies to the yaml walk. It does not apply to a regression flow.

`output.en` and `output.other` are path templates. `{form}` is `phone` or `tablet`. `{segment}` and `{pref}` come from the locale row. English uses `output.en`.

`drawer.open` is the menu content-desc id. `labels` are the ids whose texts mark the open sheet. The scrim is the full-height node beside a sheet that shows at least two of those labels. `open_if_text` are more ids that mean the drawer is open. `unblock_when_same` is a pair of ids: when they translate to the same text, tapping the first is allowed even if the second is in `never_tap`.

`launch.dismiss` is tapped once if that text is visible (an onboarding skip). `launch.sleep` is seconds after start.

Back is refused while the scrim is up, and it is refused if the resumed package leaves the app.

## Locales

The locale table is in the yaml. Do not import a Python pack map. The capture has to keep working after a translation script is removed.

The first row is `en`. `pref` is the in-app language tag. `values` is the resource directory (`values` for English). `segment` is the manual path segment under `docs/i18n/`. A non-English row with an empty segment fails `--check`. Indonesian uses `values-in` when that is the directory the app ships. Brazilian Portuguese uses `values-pt` when that is the directory the app ships. Copy the app's real map. Do not guess Android's directory from the pref tag.

`endonym_key` is a format string. `{pref}` becomes the English string id of the picker row (`lang_name_en`, `lang_name_pt_br`). The bytes come from English, and they are the same in every pack. `endonym_row` is the zero-based row when the same endonym appears twice. `en-001` is the second English row, so its index is 1. Any other pref defaults to 0.

`language.via` is `app` or `android`. Omitted means `app`. Any other value fails `--check`.

`app` opens Settings from the drawer, taps the endonym, and refuses the System default row. The menu is paged with overlap in both directions, and a miss closes the menu. It restores English at the end of a run that changed the language. One locale that fails the picker is recorded and the loop continues. `menu`, `settings`, `field`, `system`, and `ready` are required, and so is `endonym_key`.

`android` is for an app that follows the device and has no language selector of its own. The driver runs `cmd locale set-app-locales` for `package`, then force-stops that app so the next launch uses the new locale. It does not open a drawer and does not change the rest of the device. The tag is the row's `android` value when set, otherwise `pref`. A row may set `android: pt-BR` when `pref` is not the Android tag. `in`, `iw`, and `ji` are sent as `id`, `he`, and `yi`. The previous app locale list is read first and restored at the end, including an empty list. `menu`, `settings`, `field`, `system`, `ready`, and `endonym_key` are not required. The walk itself still does not force-stop the app.

A flow does not use this table. It resolves string ids from the device locale. See Regression flows.

## Sections

A section has `group` and `shots`, then `steps`. `--shots <group>` selects every filename in sections that share that group name. Each section still runs or skips on its own shots. A filename in two groups is an error. A shot step whose file is not on a section is an error. A section shot with no shot step is an error.

A section runs only when one of its shots is selected and missing, or when `--replace` is set. A block with `shots` runs only when one of those shots is selected and missing. Omit `shots` on a block to inherit the enclosing list. `shots: []` runs whenever the parent section runs. Use that for a gesture after the JPEG is already written, such as dismissing a shutter that the shot was taken before.

Flags clear at the start of each section. A flag is set only when that step succeeds. `back_if` presses Back only when the flag is set. Two `back_if` steps, one for the form and one for the picker, press Back twice only when both opened.

`on_fail` (default `note`):

- `note` records a note and continues.
- `stop` ends this device's walk.
- `skip` leaves this step list. The rest of the enclosing block does not run. The parent continues.
- `leave` presses Back until this app is resumed, then leaves this step list.

A block's `on_fail` is the default for its own steps. A step's own `on_fail` wins. A failed shot notes that file only, unless `note_shots` names other files. `note` replaces the default message.

A step has one action. `on_fail`, `flag`, `note`, and `note_shots` sit beside it.

Yaml `on_fail` is how the screenshot walk continues or stops. A regression flow rejects `on_fail`. The wrapper reads the flow report and decides pass or fail.

## Yaml steps

`drawer: nav_fill` opens the drawer and taps that string id.

`open_drawer: true` and `close_drawer: true` only move the drawer. Close taps the scrim. A failed close after the drawer shot should be `on_fail: stop`. Later sections may open the drawer again.

`tap_text` taps a string id. A string value is the id. A mapping can set `scroll` to `page` (one page, then one more try) or `fling` (until the bottom labels stop changing, at most eight). `else` is one more id, or a list, tried in order.

```yaml
- tap_text:
    id: reports_expenses_list
    else: [nav_expense_list]
    scroll: page
```

`tap_desc: some_id` taps that content description.

`tap_field: some_id` taps the clickable on that label, or the label itself.

`tap_between` taps the clickable strictly between two content-desc anchors. `1x`, `2x`, and `4x` are ignored. This is the shutter between Save and Pick, and the shutter between the two start-trip labels.

```yaml
- tap_between:
    desc: [fuel_save, fill_pick_an_existing_image]
  on_fail: skip
```

`tap_beside` is the pump control. Portrait: on the shutter row, to the right of Pick. Landscape: above the shutter. `save` and `pick` are the anchor ids. `skip` is more ids that are not the control.

`tap_matching` taps text. `contains` is a literal substring (a gallery filename such as `not-a-dash`, which is not a string id). `card: true` requires a middle dot and `yyyy-mm-dd`, which is how an expense or fill card titles its row. `skip_format` is the string id of a line that must not be taken for that card (the fills-count line). It does not scroll. Put it after the screen that shows the row.

`tap_any` taps the first visible literal. These are device-language words, not string ids (`Pictures`, `Albums`, `Folders`, `Collections`). `scroll` is `page` or `fling`. `backup` is a list of steps tried only when the first tap misses. A backup step that fails fails the whole `tap_any`. Backup does not use `on_fail`.

```yaml
- tap_any:
    text: [Pictures]
    scroll: page
    backup:
      - tap_any:
          text: [Albums, Folders, Collections]
      - wait: 0.7
      - tap_any:
          text: [Pictures]
          scroll: page
```

`swipe: page` or `swipe: fling` is one gesture.

`wait: 0.6` sleeps that many seconds. A yaml `wait` has no 2 second cap. A flow `wait` does.

`shot: file.jpg` writes that JPEG when it is selected. A mapping sets `file` and `unless`. `unless` is a list of string ids. If one of those texts is on screen, the file is not written, a note is recorded, and the walk continues. Use that when the screen is still the English failure or the "not identified" dialog.

`back: 1` presses Back that many times. `back_if: form` presses Back once when that flag was set. `back_to_app` presses Back until this package is resumed. `back_while_dialog` presses Back while the name list is up, at most twice. It needs `dialog` and `show_all` string ids. It does not tap a name.

`wait_until` waits until that name list is up. `timeout` is seconds (default 25). The name list is the title plus a clickable name that is not pack chrome. Show-all is not a name.

`wait_settle` waits out the spinner after a shutter. `kind` is `fill` or `trip`. `processing` and `retry` are string ids. `dialog` and `show_all` are the name-list ids. `between` is the two shutter anchors. `timeout` defaults to 20. A timeout still lets the walk continue.

`push_if_absent` pushes a small gray JPEG only when `remote` is missing, then asks the media scanner to see it. It does not delete.

`hook: name` calls `HOOKS["name"]`.

`block` groups steps and names the shots that justify them. `on_fail: leave` on the block becomes the default for those steps, so a gallery failure can return to the app without skipping the later shutter shots.

Take the live shot before the shutter. The shutter, the settle, and Back only dismiss the dialog. The start-trip JPEG is the live screen.

```yaml
- shot: start-trip.jpg
- block:
    shots: []
    steps:
      - tap_between:
          desc: [nav_start_trip, trip_personal_now_at_this_location]
        on_fail: skip
        note_shots: [start-trip.jpg]
      - wait_settle:
          kind: trip
          processing: ui_cancel_processing
          retry: settings_retry
          dialog: vehicle_vehicle_not_identified
          show_all: vehicle_show_all_vehicles
          between: [nav_start_trip, trip_personal_now_at_this_location]
- back: 1
```

A nested block whose shots are a subset keeps the parent from opening a screen that was not selected. A failed card tap uses `on_fail: note` and `note_shots` so the list photo still happens.

`--check` on a yaml prints `ok N shots, N locales`. A missing English id fails the check. A missing locale overlay warns.

## Regression flows

Flows are JSON, schema `capture-flow/1`, usually `regress/flows/*.json`. Repeat `--flow` to run several. A relative path is under `--root`. Every named flow is checked before any device work. One bad flow exits 2 and drives nothing.

`regress/config.json` is an object. Every key except `schema` is a variable. `strings_res` is the resource directory (default `app/src/main/res`). `activity` is the component `launch` passes to `am start -n`. The tool does not install that package, grant permissions, or force-stop it. `decoder` and `logcat_forbid` are not acted on. A missing config file is an empty object, so a flow that does not need those keys can still `--check`.

```json
{
  "pkg": "com.example.app",
  "activity": "com.example.app/.MainActivity",
  "strings_res": "app/src/main/res"
}
```

The package driven is the one the wrapper installs and the one `activity` names. A separate debug or qa applicationId is optional. An app that can test a normal install should do that.

`--var k=v` overrides a config key and a flow `vars` entry of the same name. The name is an identifier. Repeat the flag for several variables. Built-ins are applied last and win: `serial`, `sdk`, `model`, `flow`, `run_dir`, `flow_dir`. `sdk` and `model` come from `getprop`. `flow` is the flow's `name`. `run_dir` is the run directory. `flow_dir` is that flow's directory inside the run.

`${name}` is replaced in strings, including strings nested in objects and lists. A name is defined when it is a built-in, a config key, a flow `vars` key, a `--var` name, or a `save_as` from an earlier `read_text`. A `${name}` in `package`, `description`, or `vars` must already be defined. A `${name}` in a step must already be defined, and `when.var` must already be defined. The comparison value may be a literal.

```json
{
  "schema": "capture-flow/1",
  "name": "open",
  "description": "Open ${pkg}",
  "vars": {"label": "Ready"},
  "defaults": {"timeout_s": 20, "poll_s": 0.5},
  "steps": [
    {"id": "start", "launch": true},
    {"id": "go", "tap": {"res_id": "start"}, "timeout_s": 10, "scroll": "page"},
    {"id": "status", "read_text": {"string": "status_ready"}, "save_as": "status"},
    {"id": "shot", "when": {"var": "status", "eq": "${label}"}, "screenshot": "open.png"}
  ]
}
```

`schema`, `name`, and a non-empty `steps` list are required. `description`, `package`, `vars`, and `defaults` are optional. `defaults.timeout_s` defaults to 20. `defaults.poll_s` defaults to 0.5, and a value at or below 0 is treated as 0.05. `defaults.on_fail` is rejected.

A step is one object with exactly one command. Beside the command it may have `id`, `timeout_s`, `optional`, `when`, `save_as`, and `scroll`. Any other field fails `--check`. That includes `on_fail`, `compare`, `expect`, and `retry`. `save_as` is only on `read_text`. `scroll` is only on `tap`, and it is `page` or `fling`. `optional` is true or false. `timeout_s` is a number. `id` defaults to `<command>-<index>`.

`when` is `{ "var": "name", "eq": "value" }` with one of `eq`, `ne`, `gt`, `lt`, `ge`, `le`. When both sides parse as numbers, the comparison is numeric. Otherwise both sides are strings. A false `when` skips the step, records the message `when`, and is not a failure.

A selector is an object with exactly one of `res_id`, `string`, `text`, or `desc`.

- `res_id` is a layout id (`@+id` or `@id` in a `layout*` xml under `strings_res`). The value may be the bare name or a resource id. It matches `resource-id` equal to that value, or ending in `:id/<name>` or `/<name>`. A `${}` in the id skips the layout lookup. No layouts, or an id that is not in one, fails `--check`.
- `string` is an id in `values*/strings.xml`. The node's text must equal that string in the device locale.
- `text` is a literal. `contains: true` is a substring match. `contains` is only legal on `text`.
- `desc` is a string id matched against `content-desc`, or `{ "literal": "..." }`.

`index` is a non-negative integer. Omitted means 0, the first match. `within` is another selector. The search is inside the first node that matches `within`, and that node itself is included.

String ids for a device run come from `persist.sys.locale` (default `en`). The table is `values/strings.xml`, then `values-<lang>/strings.xml`, then `values-<lang>-r<REGION>/strings.xml`. `--check` unions every `values*/strings.xml` and does not ask the device. A string or desc id with `${}` skips the strings lookup.

Commands:

- `tap` is a selector. `scroll: page` keeps paging until the node is found or `timeout_s` elapses. `scroll: fling` stops early when the bottom labels stop changing. A missing node stops the run unless `optional` is true, which records `absent` and continues.
- `long_press` is a selector. It holds for 800 ms. It does not scroll. A missing node stops the run unless `optional` is true.
- `type` is `{ "into": <selector>, "text": "...", "clear": false }`. It taps the node, and `clear: true` clears the current text before typing. Spaces are sent as `%s`. A missing node stops the run unless `optional` is true.
- `swipe` is `page`, `fling`, or `{ "from": [x, y], "to": [x, y], "ms": 300 }`. `from` and `to` are fractions of the screen, each from 0 to 1. `ms` defaults to 300.
- `back` is a positive integer. That many Back presses.
- `home` is true.
- `rotate` is `portrait`, `landscape`, `reverse_portrait`, or `reverse_landscape`. Auto-rotate is turned off for the device and the user rotation is set. The tool does not restore the previous rotation.
- `wait` is a number of seconds from 0 through 2. A larger wait fails `--check`.
- `wait_for` is a selector. The node must appear before `timeout_s`. `optional: true` records `absent` and continues.
- `wait_gone` is a selector. The node must be gone before `timeout_s`. `optional: true` records `still present` and continues.
- `read_text` is a selector. It records the node's text, or an empty string and the message `absent` when the node is missing. It does not stop the run. `save_as` stores that text, including the empty string, for a later step. It dumps the UI once and does not use `timeout_s`.
- `screenshot` is a plain file name, or `{ "file": "name.png", "report": [{ "name": "title", "selector": { "string": "title" } }] }`. The file name has no slash and does not start with `.`. The PNG is written under `<flow_dir>/shots/`. Each report entry stores that node's text, or an empty string when the node is missing. A missing node does not stop the run. `compare` on a screenshot is rejected.
- `note` is a string. It is stored on the step as the message.
- `launch` is true. It runs `am start -n` with `activity` from `regress/config.json`. `--check` fails when `activity` is missing. It does not wait for the activity to draw. Follow it with `wait_for`.

`tap`, `type`, `long_press`, `wait_for`, and `wait_gone` poll every `poll_s` until `timeout_s`. The others do not poll.

A driving stop exits 1. The tool writes `<flow_dir>/shots/<id>-stop.png` (a new screencap) and `<flow_dir>/ui/<id>.xml` (the last `/sdcard/ui.xml` on the device). Later flows in the same command are not started. The report is still written.

`--run-dir` defaults to `<sandbox>/regress-runs/<YYYYmmdd-HHMMSS>-<serial>/`. A relative `--run-dir` is under `--root`. The tool appends one object to `report.json` under `runs`. An existing `report.json` that is not valid JSON, or that has no `runs` list, stops the tool with an error. `report.txt` is appended in the same directory. The report has timings, values, artifact paths, skipped steps, and whether a step stopped. It has no pass or fail field.

```json
{
  "runs": [
    {
      "tool": "script-android-app",
      "serial": "SERIAL",
      "sdk": "34",
      "model": "Pixel",
      "start": "2026-11-19T12:00:00+0000",
      "end": "2026-11-19T12:00:04+0000",
      "stopped": false,
      "reason": "",
      "flows": [
        {
          "name": "open",
          "file": "/path/regress/flows/open.json",
          "duration_ms": 1200,
          "stopped": false,
          "steps": [
            {
              "id": "start",
              "command": "launch",
              "duration_ms": 400,
              "skipped": false,
              "stopped": false,
              "message": "",
              "values": {},
              "artifacts": []
            }
          ]
        }
      ]
    }
  ]
}
```

`--check` prints `ok N flow(s)` and does not use adb. A device run needs that serial in `adb devices`.

Exit 0 means the steps were driven, or `--check` passed. Exit 1 means a step could not be driven, or the serial is not in `adb devices`. Exit 2 means the flow or the command was wrong (bad JSON, unknown field, undefined variable, flow flags mixed with manual-capture flags, or not exactly one `--serial`).

A yaml device run exits 1 when the walk fails or the serial is missing, and 0 when the walk finishes. Yaml usage errors (unknown pref, bad yaml, unknown hook) exit 1 as well. Yaml `--check` exits 0 after `ok N shots, N locales`.

## What the wrapper owns

The tool gathers. The wrapper judges. A screenshot is a screenshot, whether a later pixel check uses it or a manual publishes it.

A regression wrapper may install, clear data, grant permissions, push fixtures, pull files the app wrote, read logcat, and decide pass or fail from `report.json` plus those files. More than one wrapper is expected. wifi-mapper is the first app that will add flows. Those flows, fixtures, and oracles stay in that app. They are not part of this tool.

Setting animator duration scale to 0 for the run, and restoring it afterwards, is a wrapper option. The default is yes. This tool does not change animator scale.

The system file picker stays in each app's own flow. There is no shared `pick_file`.

A normal build does not run this tool.

## What an agent does

Write `manual-capture.yaml` at the app root when the app needs manual screenshots. Point every string id at English `strings.xml`. Put the locale table in the yaml. Run `--check` and read the `ok N shots, N locales` line.

Write `regress/config.json` and `regress/flows/*.json` when the app needs a scripted run. Run `./script-android-app --check --flow regress/flows/<name>.json` and read the `ok N flow(s)` line. Put install, fixtures, animator scale, and pass or fail in that app's wrapper.

Do not run a capture unless David asks. Do not commit full-resolution shots. Do not delete an older capture script unless David asks. Do not add a hook unless the three-way audit above says it is a real exception. Do not run `./deploy-orchestration`.
