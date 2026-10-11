# Privacy Policy: Fuel Smoothing for Torque

> DRAFT for David. It replaces the page at https://play.landing.lang.hm/fuel-smoothing/privacy/.
> Fill in before publishing: **[EFFECTIVE DATE]**. Package confirmed as `com.davidlang.torque.fuelsmoothing` (official build ID).
> It assumes patch3 is shipped, so billing connects only when Tip is tapped. If it isn't, change §2 to "connects to Google Play when you open the app's screen".

**Effective date:** [EFFECTIVE DATE]
**App:** Fuel Smoothing for Torque (`com.davidlang.torque.fuelsmoothing`), a plugin for the Torque Pro OBD-II app
**Developer:** David Lang (Google Play developer name: "David E Lang")
**Website:** https://play.landing.lang.hm/fuel-smoothing/
**Contact:** david+fuelsmoothing@lang.hm

Not affiliated with or endorsed by the developer of Torque or any vehicle manufacturer. "Torque" and vehicle brand names are trademarks of their respective owners.

## The short version
This plugin is written by one person, David Lang. It works on your phone, inside Torque, and keeps your data on your phone. It has no ads, no analytics and no tracking, and it never uploads your vehicle data. The app does have internet permission, but only because Google Play's billing library requires it for optional tips. It connects to Google Play only when you tap **Tip**. The only data I ever receive is a report you decide to email me.

## 1. What the app does by default

| Data or permission | Why | Where it stays |
|---|---|---|
| Vehicle data from Torque: fuel level, fuel flow, speed, engine RPM, ECU connection state | Calculate a smoothed fuel level, fuel remaining and distance-to-empty, and give them back to Torque | On the phone |
| Drive logs: timestamped fuel level, fuel flow, speed and smoothing state for every drive, plus fills | Learn your tank's size and gauge shape over many partial tanks. **All drive logs are kept** (not pruned) so the model can be re-learned | The app's private storage |
| Vehicle identity read from the ECU: **VIN**, calibration ID, calibration verification number, ECU name, OBD protocol, supported-PID lists | Recognize which vehicle is connected, so each vehicle's data stays in its own profile and you're warned about a wrong Torque profile | The app's private storage, per vehicle profile |
| Plugin settings and learned model (tank size guess, fill history, calibration) | Remember your setup | The app's private storage |
| Visibility of the Torque app (`org.prowl.torque`) | Find and talk to Torque | Not applicable |
| Foreground service and notifications | Keep updating while you drive, show that it's running, and ask about a possible wrong vehicle profile | On the phone |
| Run at startup | Re-arm the plugin after a reboot or update | On the phone |
| Battery optimization (only if you change it) | The app can open Android's battery settings; you decide | On the phone |

**No location.** The app has no location permission and records no GPS or location data.

**Network.** The app never sends vehicle data, drive logs or identity data anywhere. The internet, network-state and billing permissions are added by Google Play Billing (§2). Torque Pro is a separate app with its own policy and settings, including anything Torque logs or uploads.

## 2. What happens only if you opt in
**Optional tips.** If you tap **Tip**, the app connects to Google Play to show and process tips. Payment is handled entirely by Google. I receive only Google Play's purchase confirmation and order details, never your card or payment details. If you never tap Tip, the app doesn't contact Google Play.

Nothing else is opt-in. The plugin connects to no other accounts or services.

## 3. What I (the developer) receive: only reports you choose to send
**Send feedback** opens your own email app with the app version, Android version and device model filled in. You can add a description, screenshots or logs, and nothing is sent until you press send. These reports are the only data I receive. I use them only to fix problems and improve the app.

Reports may be anonymized (personal details such as VINs removed) and then kept indefinitely in the app's public source repository, for example as test cases, fixtures or code comments. You can ask me to delete any non-anonymized copies by writing to david+fuelsmoothing@lang.hm. Reports are never sold or shared.

## 4. Security
All data stays in the app's private storage, which Android isolates from other apps. The app accepts plugin messages only through Torque's plugin interface.

## 5. Retention and deletion
Data, including all drive logs and vehicle identity, stays on your phone until you clear the app's storage or uninstall it, which removes everything the app stored. Values passed into Torque are kept or deleted according to Torque's settings. For reports you sent me, see §3.

## 6. Children
The app is not directed at children under 13. I don't knowingly receive data from children.

## 7. International users
The app sends no vehicle data anywhere. Tips are processed by Google Play. Reports you email me are handled in the United States.

## 8. Changes
I'll post any changes here with a new effective date.

## 9. Contact
David Lang: david+fuelsmoothing@lang.hm

**Android log (logcat):** the plugin never writes the VIN, calibration ID or CVN to the Android system log; it logs only the last 4 VIN characters and a hash of the calibration ID, also when Verbose logging is enabled.
