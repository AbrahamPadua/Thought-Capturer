# Capture

A one-tap thought capturer for Android. Tap the icon and it's already listening. Talk for as long as you need, tap **Done**, and it saves. To-dos go to your Microsoft To Do **Tasks** list. With a Gemini key, "email my professor about the lab by Friday and buy groceries" turns into two tasks, and the first one gets Friday's date. Details and thinking out loud become an organized note in OneNote, in a section called **Capture**.

It only captures. Sorting and planning still happen in To Do and OneNote when you review them, ideally once or twice a day.

## Morning setup (about 5 minutes)

### 1. Install it

1. On the phone, open the **OneDrive** app and go to `Desktop / School stuff / Projects / Thought Capturer`.
2. Tap **Capture.apk** and open it once it downloads.
3. Android will ask you to allow installs from OneDrive. Allow it, go back, and tap **Install**.
4. Play Protect may warn about an unknown app, since you built it yourself. Tap **More details**, then **Install anyway**.

Updating from a version without OneNote notes? Install the new APK over the old one, then do step 2 again. Notes need a permission the old sign-in didn't ask for, and captures wait in the queue until you sign in.

### 2. Sign in to Microsoft

1. Open **Capture** and allow the microphone.
2. Tap the settings button (three sliders, top right) and then **Sign in to Microsoft**. The app copies a code and opens the Microsoft sign-in page.
3. Paste the code and sign in with the same account your To Do uses. It asks to manage your tasks and to create OneNote notes. This is the same app registration canvas-todo-sync uses, so the app name will look familiar.
4. Go back to Capture. It should say "Signed in."

### 3. Add your Gemini key

1. Get a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Paste it into the **Gemini API key** field in Settings. It saves when you leave the screen.

Without a key, each capture is saved as one to-do with exactly what you said, and nothing goes to OneNote.

### 4. Try it

Tap the icon and say "email professor about the lab report by Friday. Also, for the vest I'm thinking 32 motors at 40 hertz." Tap **Done**. The to-do shows up in To Do a few seconds later, and a note about the vest shows up in OneNote under **Capture**. Both keep the full text of what you said.

## Make it one tap from anywhere on the Flip

- Home screen: long-press the Capture icon and drag it to the home screen or dock.
- Side button: Settings → Advanced features → Side button → Double press → Open app → Capture. This works from any screen.
- Cover screen: install Good Lock from the Galaxy Store, then open MultiStar → I ♡ Galaxy Foldable → Launcher Widget, and add Capture. Now you can capture without opening the phone. Menu names move around between Good Lock versions.

## How it behaves

| You do | It does |
|---|---|
| Talk, pause, keep talking | Keeps listening. Each finished sentence moves up into the gray text and freezes into a new line in the plot |
| Go quiet | Keeps listening. Only you stop it |
| Tap **Done** (the white circle) | Saves; the lines fold into one and it closes |
| Tap the keyboard button or the text | Lets you type or fix a word while it keeps listening |
| Tap the lines after a speech error | Starts listening again |
| Press back, go home, or flip it shut | Saves what's there, so a thought never gets lost by accident |
| Tap **✕** | Throws it away |

The front line of the plot moves with your voice, so you can see it's hearing you. The colored blocks under the capture number show what your last capture became: yellow for each to-do, blue for a note, gray for anything still waiting to sync, red for a problem.

The screen stays on while Capture is open, so the cover screen can't time out mid-sentence. If you walk away without tapping Done, it stays on until you come back.

With no signal, captures wait in a queue and go out once you're back online, even after a reboot. If Gemini fails, that capture is still saved as one to-do with your exact words.

## When something's off

Open Settings (the sliders button). Under **Queue** it shows what's waiting and the last error.

- **"Not signed in" or "sign-in expired":** tap **Sign in to Microsoft** again. Anything waiting goes out right after.
- **Gemini error:** check the key. Those captures were saved unsplit, so nothing is missing.
- **OneNote error:** sign in again. A sign-in from before notes existed can't create them.

## Privacy

Your voice goes to the phone's speech service (Samsung's or Google's, whichever is the default). The text goes to Gemini (only if you added a key), Microsoft To Do and OneNote. The Microsoft sign-in and the Gemini key stay in the app's private storage and are kept out of phone backups.

## Building it again

The PC has the toolchain in `%LOCALAPPDATA%\Programs\jdk17` and `%LOCALAPPDATA%\Android\Sdk`. From this folder in PowerShell:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk17"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"; .\gradlew.bat testDebugUnitTest assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`. Builds from this PC share one signing key, so a new APK installs over the old one and keeps your sign-in.

## Where things are

- `app/src/main/java/app/thoughtcapturer/CaptureActivity.kt`: the listening dialog
- `app/src/main/java/app/thoughtcapturer/SaveJob.kt`: the offline queue plus the Gemini, To Do and OneNote calls
- `app/src/main/java/app/thoughtcapturer/SettingsActivity.kt`: Microsoft sign-in and the Gemini key
