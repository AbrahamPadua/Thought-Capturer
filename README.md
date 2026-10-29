# Capture

A one-tap thought capturer for Android. Tap the icon and it's already listening. Talk for as long as you need, tap **Done**, and it saves to your Microsoft To Do **Tasks** list. If you've added a Gemini key, a ramble like "email my professor about the lab by Friday and buy groceries" turns into two tasks, and the first one gets Friday's date.

It only captures. Sorting and planning still happen in To Do when you review the list, ideally once or twice a day.

## Morning setup (about 5 minutes)

### 1. Install it

1. On the phone, open the **OneDrive** app and go to `Desktop / School stuff / Projects / Thought Capturer`.
2. Tap **Capture.apk** and open it once it downloads.
3. Android will ask you to allow installs from OneDrive. Allow it, go back, and tap **Install**.
4. Play Protect may warn about an unknown app, since you built it yourself. Tap **More details**, then **Install anyway**.

### 2. Sign in to Microsoft

1. Open **Capture** and allow the microphone.
2. Tap **⚙** and then **Sign in to Microsoft**. The app copies a code and opens the Microsoft sign-in page.
3. Paste the code and sign in with the same account your To Do uses. This is the same app registration canvas-todo-sync uses, so the consent screen will look familiar.
4. Go back to Capture. It should say "Signed in."

### 3. Add your Gemini key

1. Get a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Paste it into the **Gemini API key** box in ⚙. It saves when you leave the screen.

Without a key, each capture is saved as one task with exactly what you said.

### 4. Try it

Tap the icon and say "email professor about the lab report by Friday and buy groceries." Tap **Done**. You'll see "Captured," and the tasks show up in To Do a few seconds later. The full sentence you said goes in each task's note.

## Make it one tap from anywhere on the Flip

- Home screen: long-press the Capture icon and drag it to the home screen or dock.
- Side button: Settings → Advanced features → Side button → Double press → Open app → Capture. This works from any screen.
- Cover screen: install Good Lock from the Galaxy Store, then open MultiStar → I ♡ Galaxy Foldable → Launcher Widget, and add Capture. Now you can capture without opening the phone. Menu names move around between Good Lock versions.

## How it behaves

| You do | It does |
|---|---|
| Talk, pause, keep talking | Keeps listening and adds each sentence to the box |
| Go quiet | Keeps listening. Only you stop it |
| Tap **Done** | Saves and closes |
| Tap the text box | Lets you type or fix a word while it keeps listening |
| Touch the screen around the box | Nothing |
| Tap **🎤** | Starts listening again after a speech error |
| Press back, go home, or flip it shut | Saves what's there, so a thought never gets lost by accident |
| Tap **Cancel** | Throws it away |

The screen stays on while the box is open, so the cover screen can't time out mid-sentence. If you walk away without tapping Done, it stays on until you come back.

With no signal, captures wait in a queue and go out once you're back online, even after a reboot. If Gemini fails, that capture is still saved as one task with your exact words.

## When something's off

Open **⚙**. It shows how many captures are waiting and the last error.

- **"Not signed in" or "sign-in expired":** tap **Sign in to Microsoft** again. Anything waiting goes out right after.
- **Gemini error:** check the key. Those captures were saved unsplit, so nothing is missing.

## Privacy

Your voice goes to the phone's speech service (Samsung's or Google's, whichever is the default). The text goes to Gemini (only if you added a key) and to Microsoft To Do. The Microsoft sign-in and the Gemini key stay in the app's private storage and are kept out of phone backups.

## Building it again

The PC has the toolchain in `%LOCALAPPDATA%\Programs\jdk17` and `%LOCALAPPDATA%\Android\Sdk`. From this folder in PowerShell:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk17"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"; .\gradlew.bat testDebugUnitTest assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`. Builds from this PC share one signing key, so a new APK installs over the old one and keeps your sign-in.

## Where things are

- `app/src/main/java/app/thoughtcapturer/CaptureActivity.kt`: the listening dialog
- `app/src/main/java/app/thoughtcapturer/SaveJob.kt`: the offline queue plus the Gemini and To Do calls
- `app/src/main/java/app/thoughtcapturer/SettingsActivity.kt`: Microsoft sign-in and the Gemini key
