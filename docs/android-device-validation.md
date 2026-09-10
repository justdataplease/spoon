# Android device validation

Run `connectedDebugAndroidTest` on a disposable AVD. The current Android Gradle
plugin installs for the active Android user but automatically uninstalls the app
and test package across users afterward.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
```

For an existing multi-user AVD, use a separate test user and direct instrumentation
so the test run leaves installed apps in place. The example below uses the existing
`RecipeWidgetSmoke` user 10 on `emulator-5554`; verify these IDs before running it.
The app ID is `com.spoon.app`, and the test package is `com.spoon.app.test`.

```powershell
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$testSerial = 'emulator-5554'
$testUser = 10
& $adbPath -s $testSerial shell pm list users
& $adbPath -s $testSerial shell am get-current-user
& $adbPath -s $testSerial shell pm list packages --user 0 com.spoon.app

.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
& $adbPath -s $testSerial install --user $testUser -r -t app/build/outputs/apk/debug/app-debug.apk
& $adbPath -s $testSerial install --user $testUser -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adbPath -s $testSerial shell am instrument --user $testUser -w -r com.spoon.app.test/androidx.test.runner.AndroidJUnitRunner
```

Keep the test user active for UI tests. Do not add `pm clear` or an unscoped
uninstall to this sequence. Android shares a package's APK code across users;
if another user already has `com.spoon.app` and must keep its current build,
use a disposable AVD instead.

`CatalogContractDeviceTest` keeps its database in `catalog-device-verification`
and prefixes test preferences with `verification_`. `RecipeShareDeviceTest`
opens the real activity without a signed-in account. Run both against the final
APK asset whenever catalog generation, taxonomy or filtering changes.
