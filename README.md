# FTC Logger

Android library for sending FTC Control Hub telemetry to the FTC Advanced laptop application.
The Hub discovers the laptop on UDP **5811** and connects to its TCP server on **5810**.
The library runs inside your OpMode; it does not replace the Robot Controller application.

## Install from JitPack

The hosted dependency below becomes available after the `v0.2.0` tag has been pushed
and its JitPack build has succeeded. Merge these repositories into your FTC project's
existing repository configuration:

```groovy
repositories {
    google()
    mavenCentral()
    maven {
        url 'https://jitpack.io'
        content { includeGroup 'com.github.ManosFps1pt' }
    }
    maven {
        url 'https://repo.dairy.foundation/releases'
        content { includeGroupByRegex 'dev[.]frozenmilk.*' }
    }
}
```

Add this dependency to `TeamCode/build.gradle`:

```groovy
dependencies {
    implementation 'com.github.ManosFps1pt:ftclogger:v0.2.0'
}
```

JitPack coordinates use the GitHub owner, repository, and exact release tag.
Java imports remain under `io.github.manosfps1pt.ftclogger`. Version 0.2.0 adds
NextFTC v2 motors, plain SDK motors, and SDK/NextFTC RGB indicator bindings while
preserving the original `DcMotorEx` overloads. See [hardware usage](#motors-and-rgb-indicators).

## Install locally

Requirements: JDK 17, Android SDK platform 30, and an FTC Android Studio project.
Open this repository in Android Studio to configure the SDK, or set `ANDROID_HOME`.
Builds use Gradle 8.11.1, Android Gradle Plugin 8.10.0, Java 8 bytecode, and Android API 24+.
The repository selects lint 9.4.1 independently through `android.experimental.lint.version`
so lint can read the newer Kotlin metadata in NextFTC's dependencies. This changes the
analysis tool without upgrading the Android build plugin.

From this repository on Windows:

```powershell
.\gradlew.bat publishToMavenLocal
```

In your FTC project's repository configuration, include Maven Local, Maven Central,
Google, and NextFTC's runtime dependency repository:

```groovy
repositories {
    mavenLocal()
    google()
    mavenCentral()
    maven {
        url = uri('https://repo.dairy.foundation/releases')
        content { includeGroupByRegex 'dev\\.frozenmilk.*' }
    }
}
```

Then add to `TeamCode/build.gradle`:

```groovy
dependencies {
    implementation 'io.github.manosfps1pt:ftclogger:0.2.0'
}
```

FTC RobotCore 11.2.1, protobuf-javalite 3.25.5, and NextFTC v2 hardware 0.2.1 are declared
transitively, including NextFTC's Kotlin runtime and hardware dependencies. This release
targets NextFTC v2 (`dev.nextftc.v2`), whose `NextMotor` API differs from v1; do not mix v1
and v2 hardware artifacts in the same app. SDK motor calls retain their original API. The library
does not embed the FTC SDK or require PedroPathing. It has been built against SDK 11.2.1;
other FTC SDK versions require compatibility checks. Avoid mixing protobuf-java (the full
runtime) with protobuf-javalite in the same app.

Copy [examples/LoggerExample.java](examples/LoggerExample.java) into your TeamCode package.
Call `start()` from OpMode `init()`, `publishLoop()` once per loop, and `close()` in `stop()`.
Hardware readings stay on the OpMode thread; network I/O runs on worker threads.
The laptop application must be running on a reachable network for discovery to succeed.
Select and start the OpMode through your Driver Station as usual.

## Motors and RGB indicators

The existing `addMotor(..., DcMotorEx)` and `addMotor(..., DcMotorEx, DoubleSupplier)`
methods remain available. You can mix SDK and NextFTC devices in the same client:

```java
logger.addMotor("drive.left", "Left Motor", dcMotorEx);
logger.addMotor("drive.right", "Right Motor", dcMotor);
logger.addMotor("intake", "Intake", nextMotor);
logger.addMotor("lift", "Lift", nextLiftMotor, () -> requestedLiftPower);
logger.addRgbIndicator("indicator.sdk", "SDK RGB Light", sdkServo);
logger.addRgbIndicator("indicator.next", "NextFTC RGB Light", nextRgbIndicator);
logger.addRgbIndicator("indicator.mock", "Mock RGB Light", () -> mockPosition);
```

Register devices before `start()`. Call `publishLoop()` after NextFTC's motor updates;
the logger reads telemetry and never calls `update()`, sets power, or changes colors.
[examples/HardwareBindingsExample.java](examples/HardwareBindingsExample.java) is compiled
by the independent consumer check.

All motor overloads preserve the existing six channels: requested power, applied power,
encoder ticks, ticks/second, current in amperes, and electrical power in watts. Supply
a voltage sensor with `addVoltageSensor(...)` to obtain electrical power. A plain
`DcMotor` supplies power and encoder ticks; velocity, current, and watts are unavailable
unless the actual object also implements `DcMotorEx`.

For `NextMotor`, the logger uses public NextFTC APIs. It converts encoder position and
velocity from physical units back to ticks using the motor's live `anglePerCount`.
Zero/non-finite conversion factors produce unavailable encoder readings. In throttle
mode, requested power is the throttle setpoint; in other modes it is the latest cached
controller output. Use the optional supplier if you track a separate normalized power
request. Applied power is NextFTC's cached output clipped to [-1, 1], not an independent
measurement of the motor's voltage. Logging does not initialize the motor during registration.

RGB bindings use the existing `<deviceId>.dutyCycle` channel with quantity `dutyCycle`,
unit `normalized`, and role `command`, so the FTC Advanced dashboard can already display
them. The value is the commanded servo position in [0, 1], not the physical PWM duty ratio,
measured light color, or brightness. NextFTC indicators report their cached position;
their brightness/PWM-range configuration remains owned by your OpMode. Non-finite or
out-of-range supplier values are unavailable. These helpers target goBILDA servo/PWM
RGB indicators, rather than addressable LED strips or REV Blinkin patterns.

The original FTC Advanced project already had an RGB dashboard and mock RGB commands
in `TelemetryGamepadTest`; these new helpers bind real SDK and NextFTC indicators.

NextFTC references: [installation](https://nextftc.dev/introduction/installation/),
[motors](https://nextftc.dev/hardware/actuators/motors/),
[RGB indicators](https://nextftc.dev/hardware/miscellaneous/rgb-indicator/).

## Pose sources, including PedroPathing

Register pose sources before calling `start()`. Coordinates use inches and radians:

```java
logger.addPose("localization", "Robot Pose", () -> {
    com.pedropathing.math.Pose pose = follower.pose();
    return StructuredRobotDataClient.PoseValue.of(pose.x(), pose.y(), pose.heading());
});
```

Or pass independent coordinate suppliers:

```java
logger.addPose("localization", "Robot Pose", xSupplier, ySupplier, headingRadiansSupplier);
```

The direct `Pose`, `Supplier<Pose>`, and `Follower` overloads from FTC Advanced were
removed so projects using other localizers can use the library without PedroPathing.

## Update an existing FTC Advanced project

1. Replace imports of `org.firstinspires.ftc.teamcode.data.StructuredRobotDataClient`
   with `io.github.manosfps1pt.ftclogger.StructuredRobotDataClient`.
2. Replace `org.firstinspires.ftc.teamcode.data.protocol` imports and fully qualified
   references with `io.github.manosfps1pt.ftclogger.protocol`.
3. Replace Pedro-specific pose calls with suppliers as shown above.
4. Remove the old local `data/` Java sources and add the Maven dependency.

Debugger/tool implementations and the Limelight HTTP bridge are separate integrations;
they are not included in this telemetry library. The generated messages still contain
the full debugger protocol so existing tools can migrate their imports.

To update the installed version, publish a new version, change the dependency version,
then rebuild and install the FTC Robot Controller APK on the Hub. Use explicit versions.

## Protocol

[protocol/robot_data.proto](protocol/robot_data.proto) is the schema used for this library.
Gradle automatically generates Java-lite messages with protoc 3.25.5 into `build/`.
Do not copy generated Java sources into `src/main/java`.

The Java package changed, but `ftc.robotdata.v2`, field numbers, framing, and protocol
version 2 remain unchanged, preserving compatibility with the existing laptop backend.
[docs/transport.md](docs/transport.md) describes the transport and the boundary between
network message delivery and OpMode command handling.

For Python bindings, use protoc 3.25.5 against this same schema:

```text
protoc --proto_path=protocol --python_out=PATH_TO_LAPTOP_PROTOCOL_DIR protocol/robot_data.proto
```

Keep the laptop and library schemas synchronized until they both use a single shared
release source. Never reuse protobuf field numbers for a different meaning. Unit tests
check messages captured from the original FTC Advanced Java bindings.

## Build and verify

```powershell
.\gradlew.bat assembleRelease testReleaseUnitTest lintRelease publishReleasePublicationToBuildRepository
.\gradlew.bat -p smoke-test assembleRelease
```

The second command compiles the example and an API consumer against the **published**
Maven artifact, including its transitive dependencies. If you use `local.properties`
instead of `ANDROID_HOME`, configure the same SDK path in `smoke-test/local.properties`.

Outputs:

- `build/outputs/aar/ftclogger-release.aar`
- `build/repository/io/github/manosfps1pt/ftclogger/0.2.0/` (AAR, sources, POM, metadata)
- `build/reports/tests/testReleaseUnitTest/index.html`

The GitHub build workflow runs these checks and uploads the staged Maven repository.
It does not publish a remote package automatically.

## Release with JitPack

The root `jitpack.yml` selects Java 17, builds the release AAR, runs the unit tests
and lint, and publishes the release into JitPack's local Maven repository. JitPack
then hosts those artifacts. Its `GROUP` and `VERSION` environment variables override
the publication coordinates for that build; local development keeps the group and
version from `gradle.properties`.

For version 0.2.0:

1. Run the build and consumer checks above.
2. Commit and push the release source and configuration to GitHub.
3. Confirm the GitHub Build workflow succeeds.
4. Create an annotated tag with `git tag -a v0.2.0 -m "ftclogger 0.2.0"` and push it
   with `git push origin v0.2.0`.
5. Open [JitPack](https://jitpack.io/#ManosFps1pt/ftclogger), look up the repository,
   request `v0.2.0`, and confirm its build succeeds before sharing the dependency.
6. Update consuming FTC projects to the hosted dependency, sync Gradle, and rebuild
   and install the Robot Controller APK on the Hub.

For subsequent releases, update `version` in `gradle.properties`, update the documented
dependency, run the checks, and use a new matching tag. Keep existing release tags fixed.

References: [JitPack Android guide](https://docs.jitpack.io/android/),
[JitPack build configuration](https://docs.jitpack.io/building/).

## Publish to another Maven host

The default coordinates are `io.github.manosfps1pt:ftclogger:0.2.0`.
Set the next release version in `gradle.properties` before publishing.
For a Maven host other than JitPack, configure its deployment URL and credentials.
Provide credentials through `MAVEN_USERNAME` and `MAVEN_PASSWORD`, then run:

```powershell
.\gradlew.bat publishReleasePublicationToRemoteRepository -PpublishUrl=https://YOUR_MAVEN_HOST/releases
```

Add that Maven URL to the consuming FTC project's repositories. Maven Central requires
additional namespace verification, signing, and release setup; this project does not
configure those steps. No remote credentials are stored in this repository.

## License

[MIT](LICENSE). Originally extracted from FTC Advanced.
