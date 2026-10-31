# Quick start

1. Install **Torque Pro (paid; Torque Lite does not support plugins)** and pair your OBD-II adapter.
2. Install **Fuel Smoothing for Torque**, open it once and allow notifications.
3. Set Fuel Smoothing's battery use to **Unrestricted** (Android settings → Apps).
4. In Torque, go to **Settings → Plugins** and check that Fuel Smoothing is enabled.
5. In Torque, select the **vehicle profile** for the car you're driving. Plugin data is kept per Torque profile, and the plugin checks the VIN/ECU identity against it.
6. Connect. On a new profile the **fuel source** is detected automatically; if asked, check the reading against your dash gauge and tap **Confirm** (or pick a source by hand on the setup page).
7. Optionally enter a **tank size** (gallons or litres). Tank size is the starting size for the Chunk fit until it learns its own. The plugin never changes it; each fit's learned size is shown below it. Blank means no starting size; each fit learns its own.
8. In Torque, add gauges for **[FuelSmooth] Smoothed Fuel Level** and each fit's **Corrected %**, **Remaining**, **DTE** and **Tank size**. DTE is Remaining times Torque's long-term mpg. A notice during a refit says that fit reads 1:1 until its refit finishes.
9. Set **Feed fuel level back to Torque** to Off, Smoothed, or Corrected from the Chunk, Band or Kalman fit.
10. Drive normally and keep filling up. Accuracy builds over several partial tanks. If you see a "Wrong vehicle profile?" notification, choose what to do with that drive.

The same quick start is on the app's main screen, with a link to the full manual.
