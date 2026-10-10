package com.davidlang.torque.fuelsmoothing

/**
 * odo = pin.miles + (counter − counterAtPin + resetOffset) × kmToMi.
 * A drop of 0131 or 0130, or a uint16 wrap, adds lastCounter − segmentStart
 * to the offset and appends a counterReset event. The displayed miles stay continuous.
 */
internal class Odometer {
    var miles: Double = 0.0
        private set
    private var pinMiles = 0.0
    private var counterAtPin = 0.0
    private var pinned = false
    private var offset = 0.0
    private var segmentStart = 0.0
    private var last: Double? = null
    val events = ArrayList<String>()

    fun pin(miles: Double, counter: Double) {
        pinMiles = miles
        counterAtPin = counter
        segmentStart = counter
        last = counter
        offset = 0.0
        pinned = true
        this.miles = miles
    }

    fun onCounter(which: String, counter: Double): Double {
        val prev = last
        if (prev != null && (counter < prev || uint16Wrap(which, prev, counter))) {
            offset += prev - segmentStart
            segmentStart = counter
            events.add(
                "{\"event\":\"counterReset\",\"counter\":\"$which\",\"before\":${prev.toString()},\"after\":${counter.toString()}}",
            )
        }
        last = counter
        if (pinned) {
            miles = pinMiles + (counter - counterAtPin + offset) / FuelV2.KM_PER_MILE
        }
        return miles
    }

    fun unloggedLine(gapMiles: Double, mpgRecent: Double): String? {
        if (gapMiles <= 0.5) return null
        val fuel = if (mpgRecent > 0.0 && mpgRecent.isFinite()) gapMiles / mpgRecent else 0.0
        return "{\"unlogged\":true,\"gapMiles\":${gapMiles.toString()},\"fuelGal\":${fuel.toString()}}"
    }

    private fun uint16Wrap(which: String, prev: Double, counter: Double): Boolean {
        return which == "0131" && prev >= 65000.0 && counter < 1000.0
    }
}
