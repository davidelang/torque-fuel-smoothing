# Quick start

1. Install **Torque Pro (paid; Torque Lite does not support plugins)** and pair your OBD-II adapter.
2. Install **Fuel Smoothing for Torque**, open it once and allow notifications.
3. Set Fuel Smoothing's battery use to **Unrestricted** (Android settings → Apps).
4. In Torque, go to **Settings → Plugins** and check that Fuel Smoothing is enabled.
5. In Torque, select the **vehicle profile** for the car you're driving. Plugin data is kept per Torque profile, and the plugin checks the VIN/ECU identity against it.
6. Connect. On a new profile the **fuel source** is detected automatically; if asked, check the reading against your dash gauge and tap **Confirm** (or pick a source by hand on the setup page).
7. Optionally enter a **tank size** (gallons or litres). It is only a starting guess; the learned size takes over once 10% of the gauge has been covered by two or more drives.
8. In Torque, add gauges for **[FuelSmooth] Smoothed Fuel Level**, **Corrected Fuel Level**, **Fuel Remaining** and **Plugin DTE**.
9. Set **Feed fuel level back to Torque** to Off, Smoothed, or Corrected (Chunk, Band or Kalman, chosen independently of the Gauge map); with Corrected your existing 012F gauge shows the linearized value from the map chosen in **Gauge map**.
10. Drive normally and keep filling up. Accuracy builds over several partial tanks. If you see a "Wrong vehicle profile?" notification, choose what to do with that drive.

The same quick start is on the app's main screen, with a link to the full manual.
