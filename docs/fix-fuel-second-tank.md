# Second tank: sender, MCU, and the OBD-II bus

Hardware and Torque extra-PID setup for a **second independent fuel tank**. Software for two maps / two DTE is the companion plan `sandbox/plans/fix-fuel-second-tank-20260828-1409-plan.md`. On execute, copy this file to tracked `docs/fix-fuel-second-tank.md` (do not invent a second hardware guide).

This is **not** the factory E-350 tank. Leave Ford FLI (`16C1`) and the cluster on **sender 1**.

---

## What does not work

A classic Ford-style **one analog switch** that feeds either sender into the **same** PCM/cluster FLI pin:

- Torque and Fix Fuel see **one** `%` that jumps when you flip the switch.
- That looks like a fill or a dump (fill-snap, mapping, DTE all lie).
- OBD-II `012F` is still a **single** percentage.

Do **not** switch sender 1’s wire. The pump selector may switch **which tank feeds the engine**. Both senders must stay **live at the same time**.

---

## Architecture

```
  [Tank 1 factory sender] ── unchanged ──► PCM / cluster / Ford 16C1
                                              ▲
  [Tank 2 sender] ── analog (ohms) ──► MCU ADC
  [Pump selector] ── 12 V sense ────► MCU GPIO
                         │
                         ▼
              MCU is an extra *slave* on the
              diagnostic bus (short stub at DLC)
                         │
                         ▼
         Existing ELM/STN adapter (Torque) still master
         Torque extra PID ← MCU Mode 22 response
         Fix Fuel tank-2 source = that extra PID
```

- **Analog stays under the dash** (or wherever the second sender cable is). Do not run sender ohms out to a dangling OBD dongle on the bumper.
- **MCU sits at the DLC** (short CAN/J1850 stub). Power from pin 16 through a **fuse**. Chassis ground pin 4 or 5.
- The MCU **answers** PID requests. It is **not** a second scan tool. Two ELM masters on a Y-cable will fight the PCM.

---

## Which bus is this vehicle on?

Read Torque **Adapter Status** / last protocol **before** buying parts.

| Protocol | Typical years | DLC pins | Cheap MCU path |
|----------|----------------|----------|----------------|
| **ISO 15765 CAN** | Most ~2008+ light-duty | 6 = CAN-H, 14 = CAN-L | **Yes** — ESP32 or Arduino + CAN PHY |
| **J1850 PWM** (Ford SCP) | US Ford/Lincoln ~1996–2001, **including 1999 E-350** | 2 = Bus+, 10 = Bus− | **Not** a $5 ESP32+MCP2515. See PWM section |
| J1850 VPW | Many GM ~1996–2003 | 2 = Bus+ | Same: not MCP2515 |
| ISO 9141 / KWP | Many Asian/EU older | 7 = K, 15 = L | K-line UART + ISO chip; different again |

**This van is J1850 PWM.** A CAN transceiver on pins 6/14 will sit on a **dead** pair and never see Ford FLI or Torque’s adapter traffic.

---

## CAN vehicles (the cheap path)

### Termination (do not break the bus)

CAN is a **single** twisted pair with **exactly two** 120 Ω resistors, one at each **end of the backbone**. The car already has those (often PCM and a far module, or PCM + DLC, depending on the model).

When you tap the DLC:

1. **Do not add a third 120 Ω.** Cheap MCP2515 boards usually have a terminator (solder jumper **J1**, or a resistor labelled 120R / R4). **Open the jumper / remove that resistor** before connecting to the vehicle.
2. Check with a meter, **module unplugged from the car**: CAN-H to CAN-L on the **module** should **not** read ~120 Ω if the onboard terminator is off (open / megaohms). On the **car DLC** (ignition off, module unplugged) you often see ~60 Ω (two 120 Ω in parallel). If the car already reads ~60 Ω, you must **not** add another 120 Ω.
3. **Stub length:** keep the tap **short** (tens of cm, not a 3 m pigtail). ISO 11898 stub rule of thumb is on the order of **0.3 m at 1 Mbit/s**; OBD is 250 or 500 kbit/s so a short under-dash lead is fine. Do not daisy-chain a long drop.
4. You are an **intermediate node**, not a new end of the bus. Do not move the factory terminators onto the MCU board.

### Suggested cheap boards (CAN only)

| Board | Notes |
|-------|--------|
| **ESP32-WROOM + MCP2515+TJA1050** (~$8–15 the pair) | Disable MCP2515 120 Ω jumper. Level-shift SPI if the MCP is 5 V and ESP32 is 3.3 V. Crystal is often 8 MHz — match the library. |
| **ESP32-S3 / ESP32 with TWAI** + **SN65HVD230** | On-chip CAN controller; HVD230 is 3.3 V. Many breakouts still have a 120 Ω jumper — **off**. |
| **Seeed XIAO ESP32-C3 + XIAO CAN-Bus expansion** | Pad **P1** adds 120 Ω; **leave it open** on a car. |
| **Arduino Uno + MCP2515 shield** | Works; more power draw. Same terminator rule. |
| **Longan Labs / CANBed** | Check whether termination is default-on. |

**Do not use** a second ELM327 as the tank-2 node. ELM is a **tester**. You need a **responder**.

### Firmware role (CAN)

- Listen for ISO-TP / OBD requests (functional `0x7DF` and/or a physical ID you pick, e.g. request `0x7E1` → respond `0x7E9`, **not** `0x7E8` which is usually the PCM).
- On Mode **22** (or Mode **01** if you claim a unused PID) for **your** tank-2 PID only, reply with 1–2 data bytes. Do **not** answer `012F` or Ford `16C1`.
- Suggested PID (document in Torque): Mode/PID **`22F010`**, `% = A×100/255` (or 16-bit if you want finer). Long name e.g. `[FixFuel] Tank2 Level`.
- Optional second PID: pump select `0`/`1` (`22F011`).
- Baud: US OBD CAN is usually **500 kbit/s** (ISO 15765-4). Some trucks **250 kbit/s**. If Torque already connects, match **that**.
- ACK like any CAN node. Filter so you do not flood.

### Power

DLC pin **16 is often +12 V with the key off**. An ESP32 at tens of mA will flatten the battery. Fuse the 12 V tap (**100–250 mA**). Sleep the MCU when CAN is quiet / ignition off (sense pin 16 sag, or a switched 12 V, or CAN activity). Linear 7805 on 12 V will run hot — use a small buck (e.g. Mini360) to 5 V / 3.3 V.

---

## J1850 PWM vehicles (1999 E-350 and similar)

MCP2515 / SN65HVD230 / TWAI **cannot** drive pins 2/10.

PWM is 41.6 kbaud, differential, ~5 V, Ford SCP. Hobby “J1850 Arduino” projects are mostly **readers**, not well-tested **extra ECUs**. Bit-banging a slave that must **not** corrupt PCM traffic is a research project, not a weekend BOM.

Practical options on this van:

1. **Preferred for the E-350:** MCU still reads sender 2 **analog under the dash**, but **does not inject onto PWM**. Publish tank 2 by Bluetooth (or USB) to the phone and let Fix Fuel grow a non-OBD source later. That is a software follow-on, not this bus tap.
2. **PWM-capable PHY + MCU** (expert): e.g. hobby J1850 PWM transceiver + STM32/AVR **slave** firmware that answers a Ford-style Mode 22 request the existing adapter already issues. Do **not** add CAN-style 120 Ω on pins 2/10. PWM “termination” is the factory bus impedance; extra loads cause errors Torque already counts (`getDataErrorCount`).
3. **Do not** Y-split a second ELM327 onto the PWM DLC.

If you only drive **CAN** vehicles with tank 2, skip PWM injection entirely.

---

## Analog front-end (both protocols)

Typical Ford-ish sender: roughly **empty high ohms / full low ohms** (example Super Duty service: ~180 Ω empty, ~10 Ω full). **Measure your sender** with the tank empty-ish and full before picking resistors.

```
+5 V (MCU regulated)
   |
  Rpull  (e.g. 100–220 Ω, ≥0.5 W, so the divider stays in ADC range)
   |
   +------ sender top (signal)
   |
  [sender to ground]
   |
  GND  (same ground as MCU and chassis)

ADC pin ── series 1 kΩ ── optional 3.3 V zener/clamp to MCU Vcc
```

- **Do not** parallel this divider onto sender 1’s PCM circuit.
- Fuse the sender harness if it can short to 12 V.
- Map ADC → 0–100% in firmware (calibrate empty/full). Publish that % on the PID (do not publish raw ohms to Torque unless the extra-PID equation does the math).
- Pump switch: if it is 12 V, use a divider (e.g. 10 k / 3.3 k) into a GPIO, never 12 V into the pin.

Mechanical: MCU + regulator in a small box **at the DLC**; analog pair from the second tank/sender (or a tap of a **new** sender cable) run as a dedicated pair. Strain-relieve. Keep sender wiring away from ignition coils.

---

## Torque extra PID (after the MCU answers)

Settings → Manage extra PIDs → Add custom PID (example CAN):

| Field | Example |
|--------|---------|
| Mode/PID | `22F010` |
| Long name | `[FixFuel] Tank2 Level` |
| Short | `T2` |
| Min/Max | 0 / 100 |
| Units | `%` |
| Equation | `A*100/255` |
| Header | `7E1` (physical to the MCU) or Auto if functional `7DF` works |

Optional: `[FixFuel] Tank2 Selected` / `22F011` / equation `A` / 0–1.

Enable the extra PID so Torque actually polls it (add a tiny display, or log it). Fix Fuel’s tank-2 source is this **long name**, not `012F`.

---

## Safety

- Work with the battery disconnect if you are in the DLC cavity.
- No fuel in the MCU box. Sender is electrical only.
- Fused 12 V. Assume pin 16 is live in park.
- If Torque starts throwing adapter errors after the tap, **unplug the MCU first** — extra terminator or a babbling node is the usual cause.
- Do not claim `012F` or Ford `16C1` on the bus.

---

## Checklist

1. Protocol from Torque (CAN vs PWM).
2. Sender 2 **not** switched onto PCM FLI.
3. Analog at MCU; MCU at DLC; **short** stub.
4. CAN: **terminator off** on the module; car DLC ~60 Ω unchanged.
5. Unique Mode 22 PID; Torque extra PID; Fix Fuel tank-2 source = that name.
6. Pump-select PID or a config toggle so gal/hr is charged to the **active** tank only.
