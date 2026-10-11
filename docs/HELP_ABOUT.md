# In-app Help and About text

The app has one screen (PluginActivity). Its layout is:

title → help text (`R.string.help_body`) → **Full manual (online)** button (`R.string.manual_url`, placeholder) → "Requires Torque Pro" (only when Torque isn't installed) → about line → Privacy / Send feedback / Tip buttons → settings.

patch3 puts the quick start below into `help_body` and adds the manual button.

## Help (`help_body`)
Quick start
1. Install Torque Pro (the paid version; Torque Lite does not support plugins) and pair your OBD-II adapter.
2. Enable this plugin in Torque → Settings → Plugins.
3. Allow notifications, and set battery use to Unrestricted so Torque can start this plugin in the background.
4. In Torque, select the vehicle profile for the car you are driving. Data is kept per Torque profile and checked against the vehicle's VIN/ECU identity.
5. On a new profile the fuel source is detected when you connect; if asked, compare it with your dash gauge and tap Confirm. You can also pick it below.
6. Tank size (gal or L) is only a starting guess. The learned size replaces it once 10% of the gauge is covered by 2+ drives.
7. In Torque, add gauges for [FuelSmooth] Smoothed Fuel Level, Corrected Fuel Level, Fuel Remaining and Plugin DTE. Band and Kalman maps have their own (band)/(kalman) PIDs.
8. Feed fuel level back to Torque: Off, Smoothed or Corrected (from the Gauge map you choose) onto your 012F gauge.
9. Fuel is counted only while the engine runs. The plugin runs only while Torque is connected to the ECU.
10. Keep driving and filling up; accuracy builds over several partial tanks. If a Wrong vehicle profile? notification appears, choose what to do with that drive.
11. All data stays on this phone. No GPS or location is recorded.

[Full manual (online)] → https://play.landing.lang.hm/fuel-smoothing/manual/ (placeholder; publish docs/MANUAL.md there)

## About
Fuel Smoothing for Torque, version <version>
A plugin for Torque Pro (Torque Lite does not support plugins). Not affiliated with Torque or Ian Hawkins.
All data stays on this phone; no location is recorded.
Privacy policy: https://play.landing.lang.hm/fuel-smoothing/privacy/
Feedback: Send feedback (opens email to david+fuelsmoothing@lang.hm)
