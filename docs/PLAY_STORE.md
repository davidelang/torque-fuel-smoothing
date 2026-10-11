# Play Store listing

## Short description (≤80 chars)
Slosh-free fuel gauge for Torque Pro that learns your tank's real shape.

## Full description
Fuel Smoothing is a plugin for Torque Pro. It turns your vehicle's jumpy fuel-level reading into a steady gauge and learns how much fuel your tank really holds at each point on the gauge.

Fuel sloshes when you brake, accelerate, corner or park on a slope, so raw readings can swing by 10% or more. And most tank gauges aren't linear: 1% near empty can be far more fuel than 1% at half a tank. Fuel Smoothing deals with both.

Features:
• Slosh rejection: the smoothed level changes slowly and ignores short swings.
• Fuel counted only while the engine runs (RPM above 0 and a fresh, updating flow reading); stale or held values are never counted.
• Learns your tank from fuel burned vs. gauge movement over several partial tanks. Three gauge maps (Chunk, Band and Kalman) are refit after each drive and fill; you choose which one drives the main gauges, and each also publishes its own PIDs for comparison.
• Learned tank size: replaces your initial guess once 10% of the gauge has been covered by at least two different drives.
• Published Torque PIDs: Smoothed Fuel Level, Corrected Fuel Level (linearized %), Fuel Remaining and distance to empty, plus Corrected/Remaining/DTE for the Band and Kalman maps.
• Feed back to Torque's stock Fuel Level (012F): Off, Smoothed or Corrected (Chunk, Band or Kalman), so existing gauges show it.
• Automatic fill detection, including pump pauses.
• Vehicle profiles tied to the Torque profile, with VIN/ECU fingerprinting: if the connected vehicle doesn't match, the drive is held aside and never learned into the wrong profile until you choose.
• Fuel-level source detection on first connect, with a Confirm step. Sources: SAE 012F, Ford 16C1 and F42F, GM 12C5, Chrysler 21A2 and fuel level average, Toyota cluster 2129, or any Torque extra PID by name. Brand PIDs are used only when the make or VIN matches.
• Tank size in gallons or litres; remaining fuel and distance follow Torque's units.
• Extra tanks (auxiliary tanks), each with its own PID.
• Polls only while Torque is connected to the ECU.

Good to know:
• Requires Torque Pro (paid) and an OBD-II adapter. Torque Lite does not support plugins.
• Your vehicle must report a fuel level through one of the supported sources, and a fuel-flow reading is needed for learning.
• Accuracy builds up over several partial tanks; early estimates are rough.

Privacy: all data stays on your phone. No GPS or location is recorded, and the app doesn't request location permission. Nothing is uploaded. The VIN is used only to recognize the vehicle and is not written to the system log. The internet permission comes from Google Play Billing and is used only when you tap Tip.

Not affiliated with Torque or Ian Hawkins.
