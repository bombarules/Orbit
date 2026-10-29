# Orbit

The soccer-ball home hub with Bomba Live built in: photo-real ball,
app drawer, live screen sharing of every screen on your wall code,
and full touch control of the shared screens (phone screens are
controlled through the Orbit screen control accessibility service,
computers through the Bomba Control Agent).

- Package `com.orbit.hub`, version 1.0.
- Debug builds are signed with the debug keystore in `app/`, so a
  new build installs over the old one.

## Build

GitHub Actions builds the APK on every push: open the Actions tab,
pick the latest run, download the `app-debug` artifact, unzip it,
and install `app-debug.apk`.
