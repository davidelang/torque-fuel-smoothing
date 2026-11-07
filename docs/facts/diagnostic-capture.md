# Trial vehicle diagnostics

Diagnostic capture is observational only: no fitting, fuel classification, density inference, correction output or profile editing changes.

## Storage/export
One append-only UTF-8 JSONL sidecar per ordinary recorded session:
`files/profiles/<recording-profile>/diagnostics/<session-id>.jsonl`
(extra tanks: `files/profiles/<profile>/tanks/<tank>/diagnostics/<session-id>.jsonl`).
The ordinary session id, profile id and tank id join it to existing raw flow/level/speed records. Uses the recording identity, never the settings editor selection. Quarantined recordings do not get diagnostic requests. Existing raw format/readers/frozen-run writer are unchanged; sidecars never go through SessionStore.mark or the fit input readers. The supported snapshot export copies these files automatically; writes honor DataLock so export cannot copy a half-written row. No UI strings added.

## Cadence/policy
After normal per-tick sampling, while ECU connected and no identity/fuel/source probe is pending: at most one diagnostic worker job per5seconds, no accumulated jobs. Nominal repeating slots:
A6,9D,46,A6,9D,42,A6,9D,5C,A6,9D,05.
Thus odometer and mass rate each about15seconds, context signals each about60seconds. Skipped/busy/probing periods stretch cadence; timestamps, not nominal spacing, govern later analysis. The sidecar open row saves protocol, support bitmaps from the accepted profile's stored fingerprint and each PID's advertised/unsupported/unknown state. ONLY advertised Mode01 PIDs on ISO15765 CAN are requested. Unknown/unsupported PIDs are not probed. The J1850 van gets metadata only, no extra diagnostic bus requests. Three unsuccessful attempts per PID stop that PID for the rest of that session; next session retries. No per-request event-log chatter.

Dedicated single daemon worker makes public Torque API `sendCommandGetResponse("", "01xx")` calls, without exclusive lock, adapter setup or Torque PID configuration. It never waits on the main sampling thread. A stuck Binder request cannot be forcibly timed out by this API: only that single diagnostic worker remains occupied, no timeout-retry threads/queued requests accumulate; diagnostic capture can stop until service recreation. Extra traffic can still add adapter latency; device observation is required, not claimed here. Existing Torque polling continues unchanged in code. API current-profile/ECU checks bracket each request; session identity is invalidated on close and shutdown. Late/changed-session replies are rejected, not assigned to the next session/profile. No raw/model reassignment/deletion.

## Fields and decoding
All rows carry epoch-ms times. OBD rows include queuedMs,t(request start),replyMs,elapsedMs,pid,source,header,unit,status,valid,values,sessionCurrent,attemptFailures. `valid` means a single correctly sized matching positive payload decoded, NOT physical plausibility, sensor accuracy or independence validation. Zero is valid data, not missing. Missing/NO DATA/error/no matching reply/ambiguous reply/invalid length/changed session are explicit statuses with null values. Raw matching reply frames and parsed payload bytes are saved (up to8matching frames); only expected Mode01 hex replies are retained, never arbitrary adapter text or identity replies. Multiple positive responders are conservatively ambiguous. Existing public-in-module ObdReply parser handles recognized single-frame headers/PCI and padding; unexpected layouts remain invalid, not guessed. PID bytes are unsigned big-endian:

| PID | values | Unit/scale |
|---|---|---|
|01A6|odometerKm|unsigned32/10,km|
|019D|engineFuelGps,vehicleFuelGps|AB/50 and CD/50,g/s; exactly4data bytes, not a leading support-flags byte|
|0146|ambientAirC|A-40,degC|
|0142|controlModuleV|AB/1000,V|
|015C|engineOilC|A-40,degC|
|0105|engineCoolantC|A-40,degC|

Each query also retains the main-loop context snapshot: t,speedKmh,sessionFuelGal,torqueFlowRaw,torqueFlowUnit,torqueFlowPid,primaryLevelPct. Nonfinite/missing numbers are JSON null. Context time is separate from request/reply time; these are already-read plugin fields, not extra synchronized measurements or a freshness guarantee. Torque flow source is retained explicitly; open row volumeIndependence is unknown. Do not assume volume is independent of ECU mass rate or infer density/fuel type from a ratio alone. Sparse samples/transients and reconnect gaps require careful integration; no integration/fuel inference is performed here.

Ambient/coolant/oil are distinctly labeled and are NOT fuel temperature. Genuine fuel rail temperature in PID016D has a different multi-sensor/support layout; omitted from this small first trial, along with torque/RPM duplication, exhaustive PID inventory, fuel class and vendor-specific probes. The chosen six signals prioritize actual odometer/mass flow and low-rate operating context, not scanning everything.

## Verified interfaces/references
- Bundled public `app/src/main/aidl/org/prowl/torque/remote/ITorqueService.aidl`, authoritative https://torque-bhp.com/api/ITorqueService.aidl : sendCommandGetResponse permits Mode01; current profile info and ECU state are public. No private settings API used.
- https://obd2pid.com/pids/01-A6 : four-byte odometer /10km.
- https://pelican.clutch.engineering/cars/fiat/fastback/parameters : SAE J1979 019D engine/vehicle signals at bits0..15/16..31, /50g/s; also A6,42,46,5C,05 formulas. This reference establishes standard layout, NOT Nissan measured values.
- Existing SourceProbe.kt ObdReply.dataAfter/payloadAfter, and Fingerprint.bitmaps: reused non-private APIs. No new tests/fakes/seams.

## Verification limits
Source visibility/reference/shape, whitespace and export/path review only. Not compiled, built, deployed or exercised on a phone/vehicle by this task. Person-run acceptance in sandbox research handoff; real response layout/latency/support behavior must be checked after David's build.
