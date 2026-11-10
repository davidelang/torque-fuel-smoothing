# Fuel Smoothing for Torque

Fuel Smoothing is a plugin for **Torque Pro**. It reads the fuel-level sensor, holds the displayed level steady when fuel sloshes, and learns how much fuel each part of the gauge represents. You get a steadier reading, a corrected percent, and an estimate of fuel remaining.

It is not affiliated with Torque or Ian Hawkins.

The setup screen is one scrolling page. **Help** and **About** are on the title row.

## Smoothing

Braking, accelerating, cornering, and slopes move the fuel in the tank, so the raw level can swing by 10% or more. The plugin rejects those swings and changes the displayed level only slowly, at a limited rate.

**Smoothing τ seconds** adds exponential averaging on top of that. The range is 0 to 600. Zero means no extra averaging. The default is 0.

**Max skew %/s** is the most the smoothed level may change per second while driving. It must be greater than 0 and at most 10. The default is 0.05. If the smoothed level is slow to follow a real change, raise it a little (for example 0.1).

The smoothed reading is published as **[FuelSmooth] Smoothed Fuel Level** (short name SmFuel, id ffd001), in percent. Put it on any Torque dashboard.

## Burn gate

While the engine is running, the plugin adds up the fuel used from Torque's fuel-flow reading. Fuel is counted only when RPM is above 0 and the flow reading is fresh and changing. A stale or held value is never counted. If the vehicle reports no RPM, a fresh, changing MAF reading is used instead. The first minute after connecting is a little more lenient while Torque's readings start.

Fuel is counted only while the engine runs. The plugin itself runs only while Torque is connected to the ECU, about once a second, and it stops when Torque disconnects.

Standard OBD fuel-rate readings and Torque's calculated fuel flow are always read as liters, which is how they are reported. You do not set that. Only a manufacturer-specific flow PID can need an override, `"flowUnit": "gal"` or `"l"`, in that profile's `profile.json`. There is no settings control for this yet. If fuel used looks about 3.8 times too high or too low, liters and gallons are mixed on that kind of PID.

Display of fuel remaining and distance to empty follows Torque's own units (gallons or liters, miles or km).

## Install and enable

You need Torque Pro (the paid Android app) and a working OBD-II adapter. Torque Lite does not support plugins. The fuel level has to be readable by one of the sources below.

1. Install Fuel Smoothing and Torque Pro.
2. In Torque, open **Settings → Plugins** and enable Fuel Smoothing.
3. Open Fuel Smoothing once and allow notifications.
4. In Android settings, set Fuel Smoothing's battery use to **Unrestricted**, so Torque can start the plugin in the background. If background start is denied, a "Tap to start Fuel Smoothing" notification appears. Tap it once.
5. Connect Torque to the vehicle.

If the **[FuelSmooth]** readings never appear in Torque, confirm the plugin is enabled, restart Torque, and open Fuel Smoothing once. If the plugin does not start in the background, set battery use to Unrestricted and tap the "Tap to start" notification when you see it.

## Help

![Help](images/phone/help.jpg)

**Help** opens a short quick start and **Full manual (online)**, which opens this manual.

## About

![About](images/phone/about.jpg)

**About** shows the version, the license, and the open-source libraries. **Privacy**, **Send feedback**, and **Tip** are on that page.

## Vehicle profile

![Upper setup screen](images/phone/setup-top.jpg)

Data is tied to the vehicle profile selected in Torque. A new Torque profile name gets its own plugin profile. Pick the right profile in Torque before you drive. The plugin cannot switch Torque's profile, and it cannot change Torque's tank capacity.

**Profile name** is the name of this profile. **Make** (with **Detect** beside the fuel source) is used only to decide which brand-specific fuel PIDs detection may try.

On connect, the plugin reads what the ECU provides (VIN, calibration ID, and similar). It uses that to notice when the selected Torque profile looks like a different vehicle:

- **Recording to …** means the plugin is sure this is a different, already-known vehicle. Data goes to that vehicle. Switch Torque's profile to match.
- A vehicle with a known VIN, or a new VIN, is used automatically. A different VIN under the same Torque profile is a different vehicle and gets its own profile, which starts with that Torque profile's tank size.
- A vehicle that sends no VIN is used automatically when exactly one saved no-VIN profile matches its protocol and supported-PID list, Torque already has that profile selected, and the live fuel level agrees (within 6 points) with the level last recorded for it. This takes a few seconds after connecting.
- Otherwise the plugin asks, for example **Confirm this is …** or **Which vehicle is this?**. The question is a heads-up notification that stays until answered, and the setup page shows it as a popup and a red banner. **Decide later** hides the popup while you look at other settings; the banner stays, and the popup returns the next time you open the page. Until you choose, the drive is held aside and is not learned from. If you do not choose, the drive is set aside, not deleted, and never used for learning.
- While a question is open, the dash still shows the live smoothed level, with Remaining (and distance to empty when Torque has a long-term mpg) from the tank-size field. No learned map is used until the vehicle is decided.

Each time Torque connects to the ECU, a new drive starts. The plugin does not assume anything about fuel used between drives. Readings during hard acceleration or braking, and shortly after stopping, are not trusted for learning. Fuel is still counted. Heavy work runs at the end of a drive (ECU disconnect) or after a fill. During a drive the last saved map is used.

When there is a fuel-flow reading but no valid level (no reply, a glitch, or an untrusted moment), the drive log stores the level as **-1**, never 0. Minus one means missing: every analysis skips it, and the fuel burned is still counted.

After several partial tanks, a learned map and its usable size settle. Fills and drives that cover a wide range of the gauge help the most.

## Fuel source

**Fuel source** is where the raw level comes from. It is stored per vehicle profile. A new profile starts at **None yet (Detect runs on connect)**. On the first connect the plugin tries the sources that fit the vehicle (brand-specific ones only when the make or VIN matches), one read-only request per second. A source is accepted only after several fresh, steady, non-zero readings. If two different sources agree, it is used and confirmed. Otherwise the plugin asks you, in a notification, to check it against the dash gauge and tap **Confirm**. Confirm is on the setup page and in the notification. Detection runs once per profile on its own. **Detect** on the setup page runs it again. You can also pick a source by hand.

The sources are:

- None yet (Detect runs on connect)
- SAE 012F Fuel Tank Level
- Mode 22 16C1 (Ford; uses Torque's "[FORD]Fuel Level" PID)
- Ford Mode 22 F42F
- GM Mode 22 12C5 %
- Chrysler 21A2 %
- Chrysler Fuel Level Average
- Toyota cluster 2129 (half-litre counts). The plugin logs the litres and uses a fixed level scale. Tank size is learned as for every other vehicle.
- Torque extra PID by long name

**Custom long name** is only for "Torque extra PID by long name": the exact long name of an extra PID you created in Torque.

**Additional tanks.** **Add additional tank** adds tanks, up to 8 in total. Tank 1 always stays. Each extra tank needs its own Torque extra PID (Mode 22, unique name — not 012F or Ford 16C1). Fuel use is charged only to the active tank. The active tank can be chosen directly or **From PID**, using a select PID whose value is the 1-based tank number. Do not wire a sender onto the PCM fuel-level pin; that causes false fills. Extra tanks publish `[FuelSmooth] Tank N Smoothed`, `Corrected`, `Remaining`, and `DTE`, with N the tank number. Tank 2 uses the shorter names Tank2 Smoothed, Tank2 Corrected, Tank2 Remaining, and Tank2 DTE.

A **"No fuel level this drive"** notification means the fuel source gave no usable level for a whole drive (at most one alert per drive). Check the adapter, or run **Detect**. The plugin never changes your source on its own. If the level stays blank or held, pick the correct source. For Mode 22 16C1, Torque must have the "[FORD]Fuel Level" PID. For a custom source, the long name must match exactly. The first few seconds after connecting are ignored while Torque's readings settle.

## Tank size

The **Tank size** field is blank, or a number above 0. The unit picker beside it is gal or L. The stored value is always gallons. The plugin never changes what you typed. Blank means you gave no starting size. Invalid entries are shown in red and are not saved.

Under the field, each learned map has its own size line, in gallons, and a chart. The chart's horizontal axis is smoothed percent. The vertical axis is that map's corrected percent minus the smoothed percent. A line on zero means the map matches the gauge one to one.

![Size lines and chart](images/phone/fits.jpg)

## Settings

![Lower setup screen](images/phone/settings.jpg)

| Setting | Valid values | Default | Meaning |
|---|---|---|---|
| Show fuel level in notification | on/off | on | Shows the level in the plugin's notification |
| Feed fuel level back to Torque (overrides 012F) | Off, Smoothed %, or a corrected percent | | Off writes nothing. Smoothed % writes the smoothed level onto Torque's stock Fuel Level (012F), so existing gauges show it. A corrected-percent choice is described with the map it comes from. Nothing is written until there is a valid value |
| Smoothing τ seconds | 0 – 600 (0 = no extra averaging) | 0 | Extra exponential averaging on top of slosh rejection |
| Max skew %/s | greater than 0, up to 10 | 0.05 | The most the smoothed level may change per second while driving |
| Fill jump % | 10 – 100 | 25 | How far the level must rise while stopped to count as a fill. It is set high on purpose, because slosh can exceed 13% |
| Verbose logging | on/off | off | Adds per-second lines to the Android log. Off by default |

**Odometer miles** is optional. Enter the reading and tap **Save pin** to anchor distance for this profile.

## Fills

A fill starts when the level rises at least the fill-jump percent above the smoothed level while the vehicle is stopped, and stays up for about 20 seconds. A brief sensor glitch is not a fill.

The fill continues through pauses of any length, for example when the pump stops at its limit and you re-authorize and pump again. It ends when you drive away, or when the level clearly drops again. The level recorded for the fill is the median of the final plateau, not a single reading.

If the level ever gets stuck far above the real reading, the plugin re-syncs to the real reading after about 30 seconds stopped, or 2 minutes driving.

Keep driving and filling up. Early corrected and remaining values can be off by several gallons. They improve as more partial tanks are logged.

## Data and privacy

Everything is stored on the phone, in the app's private storage: drive logs, learned maps, and profiles. All history is kept.

Logged fields are time, fuel level, fuel flow and its unit, speed, ECU status, and smoothing state. No location or GPS is recorded, and the app does not request location permission.

ECU identity (VIN, calibration ID, supported-PID lists) is stored per profile, only to recognize the vehicle. It is not written to the Android log. The log shows only the last 4 VIN characters and a hash of the calibration ID, even with verbose logging on.

No data is uploaded. The internet and billing permissions come from Google Play Billing (optional tips). The app connects to Google Play only after you tap **Tip**. **Send feedback** opens your email app with the app version, Android version, and device model filled in. You decide whether to send it.

Privacy policy: https://play.landing.lang.hm/fuel-smoothing/privacy/

## Troubleshooting

- **No [FuelSmooth] PIDs in Torque.** Enable the plugin under Torque → Settings → Plugins. Restart Torque, then open Fuel Smoothing once.
- **Plugin does not start in the background.** Set battery use to Unrestricted. Tap the "Tap to start" notification if you see it.
- **"No fuel level this drive."** The source gave no usable level for that drive. Check the adapter, or run **Detect**.
- **Level stays blank or held.** Pick the correct fuel source. Match a custom long name exactly. Ignore the first few seconds after connecting.
- **Fuel used looks about 3.8× too high or too low.** A manufacturer-specific flow PID has the wrong unit. Set that profile's `flowUnit`.
- **Data went to the wrong vehicle.** Select the right profile in Torque before the drive. Answer the vehicle question in the notification or on the setup page.
- **Corrected percent or remaining looks off.** More partial tanks tighten the estimate. Early values can be off by several gallons.
- **Smoothed level reacts slowly after a real change.** That is the max-skew limit. Raise it a little (for example 0.1).

## Chunk fit

The blue size line under **Tank size** reads **Chunk:** followed by this fit's own learned size in gallons, for example `Chunk: 16.4 gal`. Until this fit has learned a size, the line shows the tank-size field instead, still in gallons. The plugin never changes the field. If the field is blank and this fit has not learned a size, the line reads `Chunk: no size yet`.

This fit averages the short pieces of each drive across the gauge. Each piece is weighted by the fuel it covers and by how noisy it was, with a bound so one odd drive cannot dominate. It is refit at the end of each drive and after a fill, then saved and loaded at startup. Corrected percent is this fit's fuel at the current gauge divided by this fit's full-to-empty capacity, so 0% and 100% match the gauge and an overall scale error cancels out.

On **Feed fuel level back to Torque (overrides 012F)**, **Corrected % (Chunk)** writes this fit's corrected percent onto Torque's stock Fuel Level (012F). That choice is the default.

Add these to a Torque display:

| Name | Short | Unit | ID |
|---|---|---|---|
| [FuelSmooth] Corrected % (chunk) | CorrChk | % | ffd018 |
| [FuelSmooth] Remaining (chunk) | GalChk | gal or L | ffd019 |
| [FuelSmooth] DTE (chunk) | DTEChk | miles or km | ffd01a |
| [FuelSmooth] Tank size (chunk) | TankChk | gal or L | ffd01b |

Distance to empty is remaining fuel times Torque's long-term mpg. A blank or unknown mpg unit means distance to empty is not written. Remaining and distance to empty follow Torque's display units.

Until this fit has its own model, corrected percent is the smoothed percent (one to one with the gauge). Remaining, distance to empty, and tank size then use the tank-size field when that field is set. With the field blank and no learned size, those three are not written. A PID that is not written is left alone.

## Band fit

The green size line under **Tank size** reads **Band:** followed by this fit's own learned size in gallons, for example `Band: 16.4 gal`. This line never shows the tank-size field. Until this fit has its own size, it reads `Band: no size yet`.

This fit is a smooth curve of fuel against gauge position. How smooth the curve is is chosen by testing the map with one drive left out at a time. It is refit at the end of each drive and after a fill, then saved and loaded at startup. Corrected percent is this fit's fuel at the current gauge divided by this fit's full-to-empty capacity, so 0% and 100% match the gauge and an overall scale error cancels out.

On **Feed fuel level back to Torque (overrides 012F)**, **Corrected % (Band)** writes this fit's corrected percent onto Torque's stock Fuel Level (012F). Until this fit has a value of its own, that choice writes the smoothed percent instead.

Add these to a Torque display:

| Name | Short | Unit | ID |
|---|---|---|---|
| [FuelSmooth] Corrected % (band) | CorrBand | % | ffd012 |
| [FuelSmooth] Remaining (band) | GalBand | gal or L | ffd013 |
| [FuelSmooth] DTE (band) | DTEBand | miles or km | ffd014 |
| [FuelSmooth] Tank size (band) | TankBand | gal or L | ffd01c |

Distance to empty is remaining fuel times Torque's long-term mpg. A blank or unknown mpg unit means distance to empty is not written. Remaining and distance to empty follow Torque's display units.

Until this fit has its own size, corrected percent is the smoothed percent (one to one with the gauge). Remaining, distance to empty, and tank size are not written. A PID that is not written is left alone.

## Kalman fit

The orange size line under **Tank size** reads **Kalman:** followed by this fit's own size in gallons. When the model has an uncertainty, the line includes it, for example `Kalman: 16.4 ± 0.3 gal`. This line never shows the tank-size field. With no size it reads `Kalman: no size yet`, or `Kalman: no size yet ± 0.3 gal` when only the uncertainty is known.

This fit keeps a running estimate of the fuel in each percent of the gauge, updated once per drive. It also tracks how many different drives have covered each part of the gauge. Capacity is the fuel from empty to 98% of the gauge. It is refit at the end of each drive and after a fill, then saved and loaded at startup. Corrected percent is this fit's fuel at the current gauge divided by that capacity, so 0% and 100% match the gauge and an overall scale error cancels out.

On **Feed fuel level back to Torque (overrides 012F)**, **Corrected % (Kalman)** writes this fit's corrected percent onto Torque's stock Fuel Level (012F). Until this fit has a value of its own, that choice writes the smoothed percent instead.

Add these to a Torque display:

| Name | Short | Unit | ID |
|---|---|---|---|
| [FuelSmooth] Corrected % (kalman) | CorrKal | % | ffd015 |
| [FuelSmooth] Remaining (kalman) | GalKal | gal or L | ffd016 |
| [FuelSmooth] DTE (kalman) | DTEKal | miles or km | ffd017 |
| [FuelSmooth] Tank size (kalman) | TankKal | gal or L | ffd01d |

Distance to empty is remaining fuel times Torque's long-term mpg. A blank or unknown mpg unit means distance to empty is not written. Remaining and distance to empty follow Torque's display units.

Until this fit has its own size, it is a one-to-one map: corrected percent is the smoothed percent, and Remaining and distance to empty use the tank-size field from the first connection. The fit then learns from there. With the field blank, Remaining and distance to empty are not written. Tank size (kalman) shows only this fit's own size. A PID that is not written is left alone.

When a stored map for this fit cannot be read, a notification is posted once. The title is **Map refitting**. The text is "Fuel Smoothing is refitting a map for %1$s. That fit reads 1:1 until its refit finishes." `%1$s` is the profile name.
