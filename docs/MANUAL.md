# Fuel Smoothing for Torque — Manual

Fuel Smoothing is a plugin for **Torque Pro**. It reads your vehicle's fuel-level sensor, filters out the slosh that makes tank gauges jump around, and learns your tank's real shape over several partial tanks. You get a steadier fuel gauge, a "linearized" percent, and an estimate of fuel remaining.

It is not affiliated with Torque or Ian Hawkins.

## What it does
- **Smoothing.** Braking, accelerating, cornering and slopes make the fuel move around in the tank, so the raw level reading can swing by 10% or more. The plugin rejects these swings and changes the displayed level only slowly, at a limited rate.
- **Fuel counting (burn gate).** While the engine is running, it adds up the fuel used, based on Torque's fuel-flow reading. Fuel is counted only when the RPM is above 0 and the flow reading is fresh and updating; stale or held values are never counted. If your vehicle reports no RPM, a fresh, changing MAF reading is used instead. The first minute after connecting is a little more lenient while Torque's readings start.
- **Tank learning.** Over several drives and partial tanks, it compares the fuel burned with how far the gauge moved. From that it learns:
  - how many gallons each part of the gauge represents. A 1% change near empty is usually much more fuel than 1% at half a tank.
  - the usable tank size (from full down to 0% on the gauge).
- **Publishing.** It publishes the results as Torque plugin PIDs that you can put on any Torque dashboard. It can also write its value back into Torque's stock Fuel Level (012F).

## Requirements
- Torque Pro (Android, the paid version) with a working OBD-II adapter. Torque Lite does not support plugins.
- A vehicle where the fuel level can be read by one of the supported sources (see *Fuel source* below).
- For fuel counting: a fuel-flow PID available in Torque, for example Torque's calculated fuel flow rate.

## In-app help
The app's main screen shows a short quick start and a **Full manual (online)** button that opens this manual.

## Install and enable
1. Install Fuel Smoothing and Torque Pro.
2. In Torque, go to **Settings → Plugins** and make sure Fuel Smoothing is enabled.
3. Open Fuel Smoothing once and **allow notifications**.
4. In Android settings, set Fuel Smoothing's battery use to **Unrestricted**. This lets Torque start the plugin in the background. If background start is denied, you'll see a "Tap to start Fuel Smoothing" notification. Tap it once.
5. Connect Torque to the vehicle. The plugin polls only while Torque is connected to the ECU (about once per second) and stops when Torque disconnects.

## Vehicle profiles
- The plugin's data is tied to **the vehicle profile currently selected in Torque**. A new Torque profile name gets its own plugin profile automatically. Pick the right profile in Torque before you drive.
- The plugin cannot switch Torque's profile or change Torque's tank capacity. Torque's plugin interface doesn't allow it.
- When it connects, the plugin reads identifying information from the ECU (VIN, calibration ID and similar, where your vehicle supports it). It uses this to notice when the selected Torque profile looks like a different vehicle:
  - **"Recording to …"**: the plugin is sure this is a different, already-known vehicle. Data goes to that vehicle. Switch Torque's profile to match.
  - **"Wrong vehicle profile?"**: the plugin isn't sure. The drive is held aside and is *not* learned from unless you pick an action in the notification ("Use <suggested profile>" or "Create new profile"). If you don't, the drive is set aside, not deleted, and never used for learning.
- The settings screen also shows **Profile name** and **Make** (with **Detect**).

## Published PIDs (add them to a dashboard)
In Torque, add a display (gauge or digital readout) and pick these from the sensor list:

| PID | Short name | Unit | Meaning |
|---|---|---|---|
| [FuelSmooth] Smoothed Fuel Level | SmFuel | % | Raw gauge reading with slosh removed |
| [FuelSmooth] Corrected Fuel Level | CorrFuel | % | Linearized percent from the learned tank map (fuel left ÷ usable tank) |
| [FuelSmooth] Fuel Remaining | GalLeft | gal or L (follows Torque's volume unit) | Estimated fuel left |
| [FuelSmooth] Plugin DTE | pDTE | mi or km (follows Torque's distance unit) | Distance to empty (experimental) |

The four PIDs above follow the map chosen in **Gauge map** (see below). Each map also publishes its own set, so you can compare them on a dashboard:

| PID | Map |
|---|---|
| [FuelSmooth] Corrected % (band) / Remaining (band) / DTE (band) | Band fit |
| [FuelSmooth] Corrected % (kalman) / Remaining (kalman) / DTE (kalman) | Kalman fit |

Additional tanks publish `[FuelSmooth] Tank<N> Smoothed / Corrected / Remaining / DTE`.

**Distance to empty** = fuel remaining × your recent fuel economy (from Torque's mpg reading over the last part of the drive).

Until the plugin has learned enough, the Corrected and Remaining values are rough.

## Settings
| Setting | Valid values | Default | Meaning |
|---|---|---|---|
| Show fuel level in notification | on/off | on | Shows the level in the plugin's notification |
| Feed fuel level back to Torque (overrides 012F) | Off / Smoothed % / Corrected % (linearized) | Corrected | Writes that value onto Torque's stock Fuel Level (012F), so existing gauges show it. Nothing is written until the plugin has a valid value |
| Fuel source | see list | None (detected on first connect) | Where the raw level comes from. Stored per vehicle profile |
| Custom long name | text | — | For "Torque extra PID by long name": the exact long name of an extra PID you created in Torque |
| Smoothing time τ seconds | 0 – 600 (0 = no extra averaging) | 0 | Extra exponential averaging on top of slosh rejection |
| Max skew %/s | greater than 0, up to 10 | 0.05 | The most the smoothed level may change per second while driving |
| Fill jump % | 10 – 100 | 25 | How much the level must jump while stopped to count as a fill. It is set high on purpose, because slosh can exceed 13% |
| Tank size, current guess | blank or a number above 0, in gal or L (picker next to the field; stored as gallons) | Torque profile capacity | The **current guess** of the tank size. It starts from your Torque vehicle profile's tank capacity (or what you type) and is replaced with the learned size each time analysis runs (at fills and when Torque disconnects from the ECU). "Learned from your drives" appears when it shows the learned value |

| Gauge map | Chunk fit (default) / Band fit (smooth curve) / Kalman fit | Chunk fit | Which learned map drives the Corrected, Remaining and DTE PIDs and the Corrected feed to 012F |
| Verbose logging | on/off | off | Adds per-second lines to the Android log (for troubleshooting). Off by default |

Invalid entries are shown in red and are not saved.

**Automatic detection:** a new vehicle profile starts with no source. On the first connect the plugin tries the sources that fit the vehicle (brand-specific ones only when the make or VIN matches), one read-only request per second. A source is accepted only after several fresh, steady, non-zero readings. If two different sources agree, it is used and confirmed; otherwise the plugin asks you (notification) to check it against the dash gauge and tap **Confirm**. Detection runs automatically once per profile; use **Detect** on the setup page to run it again, or pick a source by hand.

**Fuel sources:**
- SAE 012F Fuel Tank Level
- Mode 22 16C1 (Ford, uses Torque's "[FORD]Fuel Level" PID)
- Ford Mode 22 F42F
- GM Mode 22 12C5 %
- Chrysler 21A2 %
- Chrysler Fuel Level Average
- Toyota cluster 2129 (reports the tank contents in half-litre steps; the plugin logs the litres and uses a fixed level scale, and the tank size is learned as for every other vehicle)
- Torque extra PID by long name

**Additional tanks:** Use **Add additional tank** to add tanks (up to 8 in total; tank 1 always stays). Each extra tank needs its own Torque extra PID (Mode 22, unique name — not 012F or Ford 16C1). Fuel use is charged only to the **active tank**. The active tank can be chosen directly or "From PID", using a select PID whose value is the 1-based tank number. Don't wire a sender onto the PCM fuel-level pin; that causes false fills.

## Units
- **Display:** fuel remaining and distance to empty follow Torque's own unit settings (gallons or liters, miles or km).
- **Fuel flow:** standard OBD fuel-rate readings and Torque's calculated fuel flow are always read as liters, which is how they are reported internally. You don't need to set anything.
- **Override:** only a manufacturer-specific flow PID can need an override, `"flowUnit": "gal"` or `"l"`, in that profile's `profile.json`. There is no settings-screen control for this yet.

## How learning works
- Each time Torque connects to the ECU, a new drive starts. The plugin doesn't assume anything about fuel used between drives.
- During a drive it splits the data into short chunks (gauge change vs. fuel burned). Readings taken during hard acceleration or braking, and shortly after stopping, are not trusted for learning. Fuel is always counted.
- Heavy calculations run only **at the end of a drive (ECU disconnect) or after a fill**. During a drive the last saved model is used.
- After several partial tanks, the map and usable tank size settle. Fills and drives that cover a wide range of the gauge help the most.
- **Missing levels.** When there is a fuel-flow reading but no valid level (no reply, a glitch, or an untrusted moment), the drive log stores the level as **-1**, never 0. -1 means "missing": it is skipped by every analysis, while the fuel burned is still counted.
- **Three gauge maps.** All three are refit at the end of each drive and after a fill, saved, and loaded at startup:
  - **Chunk fit** (default): averages the chunks per gauge band, weighting each by fuel covered and noise, with bounded influence so one odd drive can't dominate.
  - **Band fit:** a smooth continuous curve, with its smoothness chosen by leave-one-drive-out testing.
  - **Kalman fit:** a running estimate of the fuel per 1% of gauge, updated once per drive, that also tracks how many different drives have covered each part of the gauge.
  - Each map's Corrected % = its fuel at the current gauge ÷ its own full-to-empty capacity, so 0% and 100% match the gauge and an overall scale error cancels out.
- **Tank size.** The tank size you see is a guess until the plugin trusts what it has learned. It starts from Torque's profile capacity if set, otherwise from what you type. Once at least **10% of the gauge has been covered by two or more different drives**, the learned tank size (full to gauge 0, from the Kalman fit) replaces the guess. Before that, the setup page shows the running estimate as *provisional*, with gauge coverage by 1, 2 and 3 drives. You can edit the value; learning continues from it.

## Fills
- **Start.** A fill starts when the level rises at least the *Fill jump %* above the smoothed level while the vehicle is stopped, and stays up for about 20 seconds. A brief sensor glitch is not a fill.
- **Pauses.** The fill continues through pauses of any length, for example when the pump stops at its limit and you re-authorize and pump again.
- **End.** It ends when you drive away, or when the level clearly drops again.
- **Recorded level.** The level recorded for the fill is the median of the final plateau, not a single reading.
- **Stuck level.** If the level ever gets stuck far above the real reading, the plugin re-syncs to the real reading after about 30 s stopped (or 2 minutes driving).

## Data and privacy
- Everything is stored on your phone, in the app's private storage: drive logs, learned models and profiles. All history is kept.
- Logged fields: time, fuel level, fuel flow and its unit, speed, ECU status, and smoothing state. **No location/GPS** is recorded, and the app doesn't request location permission.
- ECU identity (VIN, calibration ID, supported-PID lists) is stored per profile, only to recognize the vehicle. It is not written to the Android log: the log shows only the last 4 VIN characters and a hash of the calibration ID, even with **Verbose logging** on.
- No data is uploaded. The internet and billing permissions come from Google Play Billing (optional tips). The app connects to Google Play only after you tap **Tip**. **Send feedback** opens your email app with the app version, Android version and device model filled in. You decide whether to send it.
- Privacy policy: https://play.landing.lang.hm/fuel-smoothing/privacy/

## Troubleshooting
- **No [FuelSmooth] PIDs in Torque:** check that the plugin is enabled in Torque → Settings → Plugins. Restart Torque, then open Fuel Smoothing once.
- **Plugin doesn't start in the background:** set battery use to Unrestricted. Tap the "Tap to start" notification if you see it.
- **"No fuel level this drive" notification:** the fuel source gave no usable level for a whole drive (at most one alert per drive). Check the adapter connection, or run **Detect**. The plugin never changes your source on its own.
- **Level stays blank or held:** pick the correct *Fuel source*. For the Ford 16C1 source, Torque must have the "[FORD]Fuel Level" PID. For a custom source, the long name must match exactly. The first few seconds after connecting are ignored while Torque's readings settle.
- **Fuel used looks about 3.8x too high or too low:** liters and gallons are mixed up. This should only happen with a manufacturer-specific flow PID. Set the profile's `flowUnit`.
- **Data went to the wrong vehicle:** select the right profile in Torque before the drive. Use the notification actions if you get "Wrong vehicle profile?".
- **Corrected/Remaining look off:** they improve as more partial tanks are logged. Early estimates can be off by several gallons.
- **Smoothed level reacts slowly after a real change:** this is the Max skew limit. Raise it a little (e.g. 0.1).
