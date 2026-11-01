# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

One user: the owner, a student, on a Samsung Galaxy Z Flip 7. They reach Capture mid-task, often from the cover screen with the phone closed, to get a thought out of their head in seconds and return to what they were doing. It is not built for anyone else.

## Product Purpose

One tap opens a capture screen that is already listening. The user talks, or types, until they tap Done; the text is queued and uploaded in the background. Gemini splits it into to-dos (with due dates, reminders and steps), an organized note, and a Later list of maybes. To-dos land in Microsoft To Do's Tasks list and notes in a OneNote section named Capture. Success means the thought is filed correctly without fiddling, and the user is back on task.

## Positioning

Capture is a front door, not another inbox. It files into the places the user already reviews: To Do, which their canvas-todo-sync tool also fills from Canvas and Gradescope, and OneNote. It sorts a ramble into to-dos and notes on its own, and parks the maybes ("maybe I should...") in the note instead of To Do. The one thing it shows back is a sticky note widget on the home or cover screen with those maybes on top and recent notes below, each cleared with a check.

## Operating Context

- Opened from the launcher icon, the side-button double press, or the cover screen through Good Lock MultiStar.
- Used mid-task in short bursts, sometimes in class, where typing replaces talking.
- Reviewing and planning happen later, in To Do and OneNote, once or twice a day.
- Main screen is about 360 × 840 dp. The cover screen is about 400 × 440 dp (948 × 1048 px at 380 dpi).

## Capabilities and Constraints

- Native Android in Kotlin with plain Android Views, no Compose or AndroidX. minSdk 31, targetSdk 36. A debug-signed sideload built on the owner's PC.
- Speech is one continuous recording. Android's recognizer reads it for rough live words, Gemini corrects each finished sentence and transcribes the whole recording at upload, and its loudness drives the plot.
- Recording runs until Done (save) or Cancel (discard). Pauses and touches don't stop it, leaving the app saves, and the screen stays on while it's open.
- Uploads are expedited JobScheduler jobs that survive being offline and rebooting. Failures show in Settings.
- Settings holds the Microsoft device-code sign-in, the Gemini key, and queue status with the last errors.
- The redesign covers the capture screen and Settings.

## Evidence on Hand

No logo or brand assets exist. The launcher icon is a placeholder microphone glyph on blue, and the launcher label is "Capture".

## Product Principles

1. Listening the moment it opens: nothing sits between the tap and the first word.
2. Never lose a thought: every way out except Cancel saves.
3. It is always obvious that it's hearing you.
4. It works as well on the cover screen as on the main screen.
5. Capture, file, return: the screen gets out of the way.
