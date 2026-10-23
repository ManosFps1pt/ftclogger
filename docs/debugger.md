# Reusable debugger and kS/kV calculator

Version 0.2.1 added the robot debugger to the existing `ftclogger` artifact. Version
0.2.1.1 republishes that API with a fresh JitPack version. The library
contains no annotated OpMode: copy [`examples/Debugger.java`](../examples/Debugger.java)
into your robot's TeamCode package. It registers the calculator without assuming motor
configuration names, a particular robot class, or the NextFTC robot module.

## Install on your main robot

1. Use `io.github.manosfps1pt:ftclogger:0.2.1.1` from a local/staged Maven repository, or
   `com.github.ManosFps1pt:ftclogger:v0.2.1.1` after that tag has been published to JitPack.
   Follow the repository setup in the main README, including Dairy's runtime repository.
2. Copy the example OpMode, adjust its package if needed, then build and install your
   Robot Controller application. Do not retain another OpMode with the same display name.
3. Use the matching FTC_ADVANCED laptop update. Its portable patch is
   [`integrations/ftc-advanced-0.2.1.patch`](../integrations/ftc-advanced-0.2.1.patch).
   It includes the frontend, backend, protocol source, generated Python binding, tests,
   and the same numeric fixture used by the Java tests. Apply it from the FTC_ADVANCED
   repository with `git apply --check PATH_TO_PATCH` followed by `git apply PATH_TO_PATCH`.
   Skip this if the patch is already applied. Rebuild/restart the laptop frontend/backend.

The original friction and manual-power tools remain available as public library classes.
They use configured SDK hardware names; the new calculator creates its own NextMotor by
hub and port. Existing telemetry clients retain their previous behavior.

## Lab run

1. Start **FTC Advanced Debugger** through the Driver Station, then open the laptop
   debugger and select **Motor Feedforward → kS / kV Calculator**.
2. Select the hub, port (0–3), direction, current cutoff, and **encoder counts per
   revolution of the shaft you are characterizing**, including any gearing. These
   counts must match the shaft used by your eventual velocity setpoints.
3. Review the run settings and start. Defaults are 20 increasing power levels up to
   1.0, three seconds settling plus one second averaging at each level, and 50 Hz
   acquisition. A normal run takes roughly two minutes including breakaway and stopping.
4. Review accepted/excluded operating points, the voltage-versus-speed plot, R², and
   residual RMS. Select the units used by your NextMotor velocity setpoints and copy
   the matching code. Download the raw dataset/report or use the existing storage actions.
5. Validate the gains against your shooter's chosen velocity setpoint on the robot.

The breakaway ramp starts from rest at 0.25 V/s and stops increasing at 3 V by default.
Movement requires three encoder ticks in the commanded direction and positive directional
velocity for 150 ms. The candidate voltage is held during confirmation. This is an
encoder-resolution-dependent breakaway estimate, not an exact friction intercept.
If movement is not detected within the ceiling, acquisition ends inconclusively.

Each steady point receives equal regression weight. Stationary/wrong-direction points,
incomplete windows, and windows whose speed SD or end-to-end drift exceeds 5% of mean
speed are excluded. At least three distinct valid voltages are required. The zero-output
rest baseline and accelerating samples are never fit observations.

All motion happens on the OpMode thread without sleeps. Current cutoff, invalid sensors,
lost TCP, expired operator keepalive (two seconds), abort, and OpMode stop command zero.
Completed data remains on the robot until laptop acknowledgement; reconnecting retries
transfer without executing the procedure again. Keep the OpMode running until transfer
finishes. Tool changes and subsequent runs wait for receipt and a stopped motor.

## Units and gains

The measured voltage is an estimate: **selected hub supply voltage × applied duty cycle**.
Encoder ticks remain unchanged in raw samples. Calibration converts them to rad/s for
the canonical fit:

```text
kS = estimated voltage at the confirmed movement-onset sample
kV = sum(omega * (voltage - kS)) / sum(omega * omega)
voltage = kS + kV * omega
```

NextFTC v2 0.2.1's NextMotor velocity controller outputs normalized power, and its numeric
feedforward input uses the unit of the velocity setpoint. The exporter therefore converts
the voltage-based gains to power at the displayed reference voltage (12 V by default)
and to rad/s, RPM, or degrees/s. Gains and setpoint units must match. This conversion does
not add battery compensation to NextMotor's velocity controller; the voltage-based results
remain available for a separately voltage-compensated controller. kA and gravity terms
are outside this procedure.

## Reuse from other code

Public classes live in `io.github.manosfps1pt.ftclogger.debug`:

- `SelectableOpMode`: extend `configure(Registry)` to register nested folders and tools.
  `Tool`, `ToolContext`, `ToolFactory`, `Parameter`, and `Command` provide the integration
  boundary. `FeedforwardTool.register(folder, id)` adds the calculator in one call.
- `FeedforwardConfig.builder(ticksPerRevolution, currentLimitAmps)`: immutable, validated
  settings with fluent overrides for steps, timing, breakaway, stability, and gain reference.
- `MotorTarget` and `NextMotorAdapter`: identify a hub/port and own one calibrated,
  open-loop NextMotor with zero caching tolerance. Only that motor's `update()` is called;
  the debugger never polls or clears NextFTC's global motor event loop.
- `FeedforwardMotor`: implement `read()` and immediate `power(duty)` to use another motor
  implementation. Readings include the actually applied duty, supply voltage, current,
  encoder position, and velocity. The caller owns loop scheduling and cleanup.
- `FeedforwardBenchmark`: supply monotonic nanoseconds to `tick(now)`, maintain operator
  presence with `keepalive(now)`, and call `abort(reason)`/zero output when your host stops.
  `completedDataset()` retains immutable samples; `result()` returns the Java analysis.
- `FeedforwardResult.analyze(samples, config)`: independently analyze saved rows, inspect
  each point's exclusion reason, or call `nextMotorGains(unit, referenceVoltage)`.
- `Benchmark`, `FrictionBenchmark`, `FrictionTool`, and `DebugRunTransfer`: reusable
  procedure, legacy friction tool, and acknowledged dataset-delivery infrastructure.

See [`examples/VelocityFeedforwardExample.java`](../examples/VelocityFeedforwardExample.java)
for a compiled consumer of the result and NextMotor unit APIs.

The canonical protocol adds only field 10 (`default_value`) to
`DebugCommandArgumentDefinition`; existing field numbers and protocol version 2 remain
unchanged. The laptop accepts an `inputs` map on `POST /api/debug/runs`, validated against
the advertised types, enum choices, and numeric bounds. Legacy flat friction request
fields are still accepted. The new benchmark/analyzer IDs are `motor.feedforward.v1`
and `feedforward.v1`; old friction reports keep their original interpretation.

## Verification

```powershell
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease publishReleasePublicationToBuildRepository
.\gradlew.bat -p smoke-test assembleRelease
```

In FTC_ADVANCED, run the new `web_driver_station.backend.test_feedforward` module plus
the existing debugger, command, and protocol tests, and build both frontend projects.
The shared `feedforward.csv` fixture covers known coefficients, varying supply voltage,
an excluded unstable window, and angular gain conversion. JVM tests also cover the
procedure, reverse feedback, output/sample alignment, current/sensor/keepalive faults,
fine hardware output increments, configuration restoration, duplicate runs, missing
hubs, and acknowledgement retries. Physical validation on the selected motor remains
the final lab check.
