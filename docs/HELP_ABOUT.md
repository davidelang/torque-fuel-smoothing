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
5. On a new profile the fuel source is detected when you connect; if asked, compare it with your dash gauge and tap Confirm. Confirm is on the setup page and in the notification. You can also pick it below.
6. Tank size (gal or L) is only a starting guess. The learned size replaces it once 10% of the gauge is covered by 2+ drives.
7. In Torque, add gauges for [FuelSmooth] Smoothed Fuel Level, Corrected Fuel Level, Fuel Remaining and Plugin DTE. Band and Kalman maps have their own (band)/(kalman) PIDs. After an update, an old Kalman map may be rebuilt from saved drives, with a notice; Kalman gauges use Chunk values until that finishes, or until the next drive is saved when there is nothing to rebuild from.
8. Feed fuel level back to Torque: Off, Smoothed, or Corrected from Chunk, Band or Kalman (independent of the Gauge map) onto your 012F gauge.
9. Fuel is counted only while the engine runs. The plugin runs only while Torque is connected to the ECU.
10. Keep driving and filling up; accuracy builds over several partial tanks. If a Wrong vehicle profile? notification appears, choose what to do with that drive.
11. All data stays on this phone. No GPS or location is recorded.

[Full manual (online)] → https://play.landing.lang.hm/fuel-smoothing/manual/ (placeholder; publish docs/MANUAL.md there)

## About
Fuel Smoothing for Torque
Version %1$s (%2$s)
A plugin for Torque Pro. Not affiliated with Torque or Ian Hawkins.
All data stays on this phone; no GPS or location is recorded.

License
Copyright David Lang. All rights reserved.

Open source libraries
This app uses the libraries below. Their licenses are separate from the copyright above.

Google Play Billing (com.android.billingclient:billing and billing-ktx 8.0.0)
Android Software Development Kit License
https://developer.android.com/studio/terms.html

AndroidX activity 1.2.3, core 1.2.0, fragment 1.1.0, lifecycle 2.0.0 and 2.3.1, savedstate 1.1.0, versionedparcelable 1.1.0, arch.core 2.1.0, customview 1.0.0, loader 1.0.0, tracing 1.0.0, viewpager 1.0.0
Apache License 2.0
https://www.apache.org/licenses/LICENSE-2.0

kotlinx-coroutines-core 1.6.0
Apache License 2.0
https://www.apache.org/licenses/LICENSE-2.0

Kotlin standard library
Apache License 2.0
https://www.apache.org/licenses/LICENSE-2.0

Google Play services (com.google.android.gms), required by Play Billing
Android Software Development Kit License
https://developer.android.com/studio/terms.html

Privacy policy: https://play.landing.lang.hm/fuel-smoothing/privacy/
Feedback: Send feedback (opens email to david+fuelsmoothing@lang.hm)
