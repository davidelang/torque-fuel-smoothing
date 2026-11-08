package com.davidlang.torque.fuelsmoothing.fillbridge;
interface IFillEventBridge {
    String getCapabilities();
    String submitFillBatch(String batchJson);
}
