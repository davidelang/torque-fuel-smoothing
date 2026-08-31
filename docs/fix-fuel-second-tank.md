# Extra tanks: sender, MCU, and the OBD-II bus

Hardware and Torque extra-PID setup for **one or more additional independent fuel tanks** (tank 2, 3, …). Software is Fix Fuel’s **Add additional tank** (not a single checkbox). Companion plans: `sandbox/plans/fix-fuel-second-tank-20260828-1409-plan.md` (first extra tank) and `sandbox/plans/fix-fuel-n-tanks-pluginactivity-20260831-0959-plan.md` (N tanks). On execute, copy this file to tracked `docs/fix-fuel-second-tank.md` (do not invent a second hardware guide).

This is **not** the factory E-350 tank. Leave Ford FLI (`16C1`) and the cluster on **sender 1** (stock tank). Each extra tank needs its **own** sender, always live.

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
  [Tank N sender] ── analog (ohms) ──► MCU ADC (one channel per extra tank)
  [Pump selector] ── 12 V / position ─► MCU GPIO(s) → **tank id 1..N**
                         │
                         ▼
              MCU is an extra *slave* on the
              diagnostic bus (short stub at DLC)
                         │
                         ▼
         Existing ELM/STN adapter (Torque) still master
         Torque extra PIDs ← MCU Mode 22 (one level PID per extra tank + select)
         Fix Fuel extra-tank source = that extra PID long name
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
- On Mode **22** for **your extra-tank PIDs only**, reply with 1–2 data bytes. Do **not** answer `012F` or Ford `16C1`.
- Suggested PIDs (document in Torque): Mode/PID **`22F010`** tank 2 `% = A×100/255`; **`22F012`** tank 3, **`22F013`** tank 4, … (one unique PID per extra tank). Long names e.g. `[FixFuel] Tank2 Level`, `[FixFuel] Tank3 Level`.
- Pump select PID **`22F011`**: value = **1-based tank id** the engine is drawing from (`1` = stock, `2` = first extra, …). Not a 0/1 “tank 2” bit.
- Baud: US OBD CAN is usually **500 kbit/s** (ISO 15765-4). Some trucks **250 kbit/s**. If Torque already connects, match **that**.
- ACK like any CAN node. Filter so you do not flood.

### Power

DLC pin **16 is often +12 V with the key off**. An ESP32 at tens of mA will flatten the battery. Fuse the 12 V tap (**100–250 mA**). Sleep the MCU when CAN is quiet / ignition off (sense pin 16 sag, or a switched 12 V, or CAN activity). Linear 7805 on 12 V will run hot — use a small buck (e.g. Mini360) to 5 V / 3.3 V.

---

## 1999 E-350: what you can actually do

Factory path (already working): Torque’s Bluetooth dongle on the DLC, extra PID **`[FORD]Fuel Level`** (Mode 22 **`16C1`**), Fix Fuel tank 1.

Each extra tank on **this van**:

| Approach | On a 1999 E-350? |
|----------|------------------|
| Leave sender 1 / `16C1` / cluster alone | **Yes — required** |
| Analog from sender 2 into an MCU under the dash | **Yes** (ohms → ADC; same as any year) |
| Pump **selector** as a 12 V GPIO into that MCU (do not switch the PCM FLI wire) | **Yes** |
| Cheap ESP32 + MCP2515 / SN65HVD230 on DLC pins **6/14** | **No.** Those pins are idle. This van talks **J1850 PWM** on pins **2/10**. |
| Same cheap CAN kit answering a Mode 22 PID so Torque can poll tank 2 | **No** — wrong PHY |
| Second generic Bluetooth ELM on a Y-cable | **No** — two **testers**; they collide with the PCM and each other |
| MCU as a **PWM slave ECU** (answers Mode 22 on pins 2/10) | Possible in theory; **not** a weekend / $10 BOM (see below) |
| MCU **does not touch PWM**; BLE/USB to the phone; Fix Fuel reads extra tanks as a non-OBD source | **Yes** — best fit for this van; needs a small plugin follow-on (not “query extra PID”) |

There is **no CAN terminator** to get wrong on this DLC. PWM is a different electrical standard (factory bus impedance). Do not solder 120 Ω across pins 2/10.

---

## Why a $10 Bluetooth OBD dongle works, but a cheap MCU tap does not

The dongle is not “good at Ford PWM because Bluetooth is easy.” It is an **ELM327 / STN11xx scan-tool chip** with a **multi-protocol analog front end** already in silicon:

- J1850 PWM (Ford, pins 2+10, 41.6 kbit/s)
- J1850 VPW (GM, pin 2)
- ISO 9141 / KWP (K-line)
- ISO 15765 CAN (pins 6+14)

Torque sends UART/`AT` commands (`ATSP1` = PWM, or auto-detect). The chip is the **tester / master**: it **asks**, the PCM **answers** `16C1`. That is exactly the role OBD was designed for. You never implemented PWM; you bought a part whose job is “be a scan tool.”

Adding extra tanks on the **same bus so Torque can poll them** is the **opposite** role:

| | Bluetooth dongle (what you have) | Tank-2 MCU (what the CAN recipe does) |
|--|----------------------------------|----------------------------------------|
| Role | Master / tester | Slave / extra ECU(s) |
| Chip | ELM/STN PWM+CAN PHY | MCP2515 or TWAI = **CAN only** |
| Pins | 2/10 on this van | 6/14 (unused here) |
| Traffic | Initiate Mode 22, wait | Hear the **dongle’s** request, **reply** with a **new** PID, **not** steal `16C1` |
| Timing | Hidden in the ASIC | PWM pulses, IFR, Ford SCP addressing; hobby code is mostly **sniffers**, not slaves |
| Failure mode | Clone firmware flaky but common | Wrong PHY: silence. Right PHY, bad slave: **PCM errors**, Torque `getDataErrorCount` up |

So: “generic dongles work” proves **this van’s PWM is reachable by a scan tool**. It does **not** prove that an ESP32 CAN transceiver can **join** that bus as another module. CAN 120 Ω / MCP2515 advice applies to **~2008+ CAN cars**, not this DLC.

A second ELM on a splitter is still two masters. It will not look like a fuel-level ECU.

Expert-only PWM injection: a real J1850 PWM transceiver (not a CAN PHY) plus MCU firmware that **only** answers your extra-tank PIDs. Do not add CAN termination. Extra DC load on Bus+/Bus− will show up as adapter errors — unplug first if Torque starts failing.

---

## J1850 PWM vehicles (other than the E-350 notes above)

Same rules as the van: MCP2515 / SN65HVD230 / TWAI **cannot** drive pins 2/10. PWM is 41.6 kbaud, differential, ~5 V, Ford SCP.

Practical options:

1. **Preferred on PWM:** analog under the dash, MCU **off** the vehicle bus, BLE/USB into Fix Fuel later.
2. **PWM PHY + slave firmware** (expert).
3. **Do not** Y-split a second ELM327.

If extra tanks are only for a **CAN** vehicle, use the cheap CAN path and ignore PWM.

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

- **Do not** parallel this divider onto sender 1’s PCM circuit. One divider/ADC **per extra sender**.
- Fuse the sender harness if it can short to 12 V.
- Map ADC → 0–100% in firmware (calibrate empty/full). Publish that % on **that tank’s** PID (do not publish raw ohms to Torque unless the extra-PID equation does the math).
- Pump switch: if it is 12 V, use a divider (e.g. 10 k / 3.3 k) into a GPIO, never 12 V into the pin. Encode the position as tank **id** 1..N on the select PID.

Mechanical: MCU + regulator in a small box **at the DLC**; analog pair **per extra tank** (or a tap of a **new** sender cable) run as dedicated pairs. Strain-relieve. Keep sender wiring away from ignition coils.

---

## Torque extra PIDs (after the MCU answers)

Settings → Manage extra PIDs → Add custom PID **per extra tank** (example CAN):

| Field | Example (tank 2) | Next extra |
|--------|------------------|------------|
| Mode/PID | `22F010` | `22F012`, `22F013`, … |
| Long name | `[FixFuel] Tank2 Level` | `[FixFuel] Tank3 Level`, … |
| Short | `T2` | `T3`, … |
| Min/Max | 0 / 100 | 0 / 100 |
| Units | `%` | `%` |
| Equation | `A*100/255` | same |
| Header | `7E1` (physical to the MCU) or Auto if functional `7DF` works | same |

Pump select: `[FixFuel] Tank Select` / `22F011` / equation `A` / min 1 / max N — value is the **active tank id** (1 = stock).

Enable each extra PID so Torque actually polls it (tiny display, or log it). In Fix Fuel: **Add additional tank**, set that tank’s source to the **long name**, not `012F`. Set Active tank to **From PID** and the select-PID name to `[FixFuel] Tank Select`.

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
5. Unique Mode 22 PID **per extra tank**; Torque extra PIDs; Fix Fuel **Add additional tank** → source = that long name.
6. Pump-select PID (1-based tank id) or the Active-tank spinner so gal/hr is charged to the **active** tank only.
