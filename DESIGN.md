---
name: Capture
description: A one-tap voice and text capture screen that draws your voice as a record-sleeve pulse plot.
colors:
  ink-ground: "#0B0B0B"
  ink-line: "#F2F2F2"
  ink-muted: "#8A8A8A"
  ink-faint: "#2E2E2E"
  code-todo: "#F2C14E"
  code-note: "#4F9DDE"
  code-error: "#E5484D"
  code-wait: "#8A8A8A"
typography:
  title:
    fontFamily: "Barlow Condensed SemiBold"
    fontSize: "34sp"
    fontWeight: 600
    letterSpacing: "0.04em"
  now:
    fontFamily: "Barlow Medium"
    fontSize: "32sp"
    fontWeight: 500
    lineHeight: 1.02
  said:
    fontFamily: "Barlow"
    fontSize: "20sp"
    fontWeight: 400
    lineHeight: 1.1
  body:
    fontFamily: "Barlow"
    fontSize: "17sp"
    fontWeight: 400
    lineHeight: 1.15
  hint:
    fontFamily: "Barlow"
    fontSize: "15sp"
    fontWeight: 400
  label:
    fontFamily: "Barlow Condensed SemiBold"
    fontSize: "14sp"
    fontWeight: 600
    letterSpacing: "0.28em"
  button-label:
    fontFamily: "Barlow Condensed SemiBold"
    fontSize: "15sp"
    fontWeight: 600
    letterSpacing: "0.16em"
  code:
    fontFamily: "Barlow Condensed SemiBold"
    fontSize: "44sp"
    fontWeight: 600
    letterSpacing: "0.12em"
rounded:
  none: "0dp"
  ring: "50%"
spacing:
  strip-gap: "4dp"
  tight: "10dp"
  md: "16dp"
  section: "20dp"
  margin: "24dp"
  divider-gap: "32dp"
  done-flank: "36dp"
components:
  button-done:
    backgroundColor: "{colors.ink-line}"
    textColor: "{colors.ink-ground}"
    rounded: "{rounded.ring}"
    size: "80dp"
  button-done-cover:
    backgroundColor: "{colors.ink-line}"
    textColor: "{colors.ink-ground}"
    rounded: "{rounded.ring}"
    size: "72dp"
  button-ring:
    backgroundColor: "transparent"
    textColor: "{colors.ink-line}"
    rounded: "{rounded.ring}"
    size: "64dp"
  button-ring-cover:
    backgroundColor: "transparent"
    textColor: "{colors.ink-line}"
    rounded: "{rounded.ring}"
    size: "56dp"
  button-ring-small:
    backgroundColor: "transparent"
    textColor: "{colors.ink-line}"
    rounded: "{rounded.ring}"
    size: "48dp"
  button-hairline:
    backgroundColor: "transparent"
    textColor: "{colors.ink-line}"
    typography: "{typography.button-label}"
    rounded: "{rounded.none}"
    padding: "0 20dp"
    height: "52dp"
  input-underline:
    backgroundColor: "transparent"
    textColor: "{colors.ink-line}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "12dp 0"
  code-block:
    backgroundColor: "{colors.code-todo}"
    rounded: "{rounded.none}"
    width: "18dp"
    height: "6dp"
---

# Design System: Capture

## Overview

**Creative North Star: "The Pulse Sleeve"**

Capture looks like a record sleeve: a matte black ground, white hairlines, and a stacked pulse plot that your voice draws. The live front line follows the microphone level. Each finished sentence freezes into one more white line behind it, so the screen shows two things: that you are being heard, and how much you have said. Everything else stays quiet so the plot can carry the screen.

The system is monochrome with one exception. Status is spoken in a coded strip of small hue blocks (yellow to-dos, blue note, red problem, gray waiting), the only saturated color in the app. Type splits in two: Barlow Condensed in tracked caps for labels and catalog numbers, Barlow in sentence case for everything the user reads or says. Controls are 1.5dp hairline rings and rectangles with drawn stroke icons. The only filled white object on any screen is Done.

Capture has one dark theme, and it is not a night mode. The black is the world, so there is no light variant. Density is low. The main screen gives the plot the whole flexible middle, and the cover screen keeps the same parts at smaller sizes.

**Key Characteristics:**
- Matte black ground, white hairline drawing, gray for secondary text.
- Hue appears only as coded status blocks, never as decoration or accent.
- Tracked condensed caps for labels; sentence case Barlow for captions and content.
- 1.5dp hairline rings and rectangles; one filled white Done per capture screen.
- The stacked pulse plot is the signature and takes all the free vertical space.

## Colors

A black-and-white sleeve palette with four status hues that only ever appear as small coded blocks.

### Primary
- **Sleeve White** (ink-line): the drawing color. Primary text, hairline strokes on every ring and rectangle, the pulse lines, drawn icons, and the fill of Done. Also the theme's accent and activated-control color.

### Neutral
- **Matte Black** (ink-ground): the window and every screen background. The pulse plot also fills each line's underside with it, so front ridges hide the lines behind them. It also tints the check icon inside Done.
- **Graphite Gray** (ink-muted): secondary text (hints, status line, finished sentences, input hints) and the field underline at rest.
- **Faint Rule** (ink-faint): 1dp section dividers in Settings and the 1dp empty slots of the pulse plot. This is a non-text color only; it is too dark for type.

### Status codes
- **To-do Yellow** (code-todo): one block per to-do from the last filed capture, up to 4.
- **Note Blue** (code-note): one block when the last capture made a note. At 40% alpha it is also the text selection highlight.
- **Signal Red** (code-error): a microphone problem (first in the strip) or a sync error.
- **Waiting Gray** (code-wait): one block per job waiting to upload, up to 3. It has the same value as Graphite Gray, but the role is separate.

### Named Rules
**The Coded Hue Rule.** Saturated color shows up only as status blocks in the code strip (18 × 6dp, square, 4dp apart, read left to right: mic error, to-dos, note, sync error, waiting). Never use a code hue for text, buttons, icons, or decoration. Any screen that shows the strip must also explain it in words, either through the strip's content description or a legend line.

**The One Fill Rule.** Sleeve White fills exactly one control per capture screen: Done. Every other control is a 1.5dp hairline on Matte Black.

## Typography

**Display / Label Font:** Barlow Condensed SemiBold (bundled, OFL)
**Body Font:** Barlow Regular and Barlow Medium (bundled, OFL)

**Character:** Barlow Condensed in wide-tracked caps has the look of catalog lettering on a sleeve spine. Barlow in sentence case is plain and readable for the words the user is saying.

### Hierarchy
- **Title** (Condensed SemiBold 600, 34sp, 0.04 tracking, sentence case): screen titles. Settings is the only one.
- **Now** (Barlow Medium 500, 32sp, 1.02 line height): the sentence being spoken right now, in Sleeve White. Up to 4 lines on the main screen. On the cover screen it auto-sizes from 24sp down to 15sp in a fixed 60dp, 2-line box.
- **Said** (Barlow 400, 20sp, 1.1 line height): finished sentences in Graphite Gray, above Now. Italic while a sentence still shows the recognizer's rough words, upright once Gemini's exact words replace it. Up to 5 lines on the main screen, keeping the newest words and cutting older ones with an ellipsis; 15sp and 2 lines on the cover screen.
- **Body** (Barlow 400, 17sp, 1.15 line height): Settings content in Sleeve White.
- **Hint** (Barlow 400, 15sp): status line and explanations in Graphite Gray. 13sp, 2 lines, ellipsized on the cover screen.
- **Label** (Condensed SemiBold 600, 14sp, 0.28 tracking, all caps): the catalog number (`Capture · 015`, then `015 filed`) and Settings section names. 13sp on the cover screen.
- **Button label** (Condensed SemiBold 600, 15sp, 0.16 tracking, all caps): text buttons.
- **Code** (Condensed SemiBold 600, 44sp, 0.12 tracking): the Microsoft device sign-in code, selectable.

### Named Rules
**The Two Voices Rule.** Labels are Condensed tracked caps and identify things (catalog numbers, section names, button actions). Captions and content are Barlow in sentence case and are written as plain sentences. Never set a caption in caps, and never use a label as a decorative kicker over a heading.

## Layout

Screens are one vertical column with no grid. The main capture screen (height 600dp and up) uses 24dp side margins, 12dp top padding, and 28dp bottom padding. From top to bottom: the top row (catalog label with the code strip 10dp under it on the left, a 48dp settings ring on the right), the status hint 36dp below, finished sentences, the current sentence, the pulse plot, which takes all remaining height (weight 1), and the button row 24dp below the plot.

The cover screen (about 400 × 440dp, served from the default layout) keeps the same parts and ids but tightens everything: 18dp margins, 6dp top and 12dp bottom padding, 10dp gaps, a 2-line hint, a 2-line said field, a fixed 60dp auto-sizing now line, and 56/72dp buttons with 28dp between them.

Settings scrolls. It uses 24dp margins with 20dp top and 40dp bottom padding. Sections are separated by 1dp Faint Rule dividers with 32dp above each one. Each label sits 20dp below its divider, and its content starts 10 to 14dp below the label.

All screens use `fitsSystemWindows` and keep the screen on while capturing. The spacing steps actually reused are 4, 10, 16, 20, 24, 32, and 36dp.

## Elevation & Depth

The system is flat. There are no shadows or elevation, and `stateListAnimator` is nulled on text buttons. The only depth is inside the pulse plot. Lines are drawn back to front, and each line with a ridge fills its underside with Matte Black, so a front ridge covers the lines behind it. Depth comes from overlap, not from light.

### Named Rules
**The Occlusion Rule.** Depth is drawn as overlap in Matte Black, never as shadow, blur, or glow.

## Shapes

There are two shapes. Full circles (50%) are used for icon controls: the settings, cancel, and type rings and the filled Done. Sharp rectangles (0dp radius) are used for everything else: text buttons, code blocks, dividers, and the field underline. Strokes are 1.5dp on every control and frozen pulse line, 3dp on the live line, and 1dp on empty plot slots and dividers. Icons are drawn 24dp vectors with 1.5dp round-capped strokes (the check is 1.75dp). They are outlines, never filled glyphs. The one exception is the tune icon, whose knobs are filled with Matte Black so they cut the lines they sit on.

## Components

### Buttons
- **Done (primary):** a filled Sleeve White circle, 80dp (72dp on the cover screen), with a Matte Black check, centered at the bottom between the other two rings with 36dp (28dp) on each side. Its ripple is Matte Black at 20%.
- **Ring (icon control):** a transparent circle with a 1.5dp Sleeve White stroke holding a drawn icon. Cancel and Type are 64dp (56dp on the cover screen). Settings is 48dp. Its ripple is Sleeve White at 20%.
- **Hairline (text button):** a sharp rectangle with a 1.5dp Sleeve White stroke, at least 52dp tall, 20dp side padding, and a Button label in tracked caps. Same ripple as the ring. Used in Settings (`Sign in again`).

### Inputs / Fields
- **Underline field:** a single 1.5dp bottom stroke in Graphite Gray at rest and Sleeve White when focused, with Body type and 12dp vertical padding. The other three edges are pushed outside the bounds.
- **Typing field:** a separate field below Said, in Body type over a hairline underline, shown only after the Type button. Typed words stay out of the spoken transcript, so a late correction of a spoken sentence can never overwrite them.

### Code Strip
- A row of 18 × 6dp square blocks with 4dp gaps, 10dp under the catalog label (14dp under the Queue label in Settings). Order and caps are covered by the Coded Hue Rule. Its content description reads the status in words (for example, "Last capture filed 2 to-dos and a note. 1 waiting to sync"). The strip is removed from layout when it has no blocks, so it never leaves a gap. That empty state is implemented but has not been captured on a device.

### Pulse Plot (signature)
- A stack of horizontal lines across the full content width. The front line is live: it follows the recognizer's level, smoothed toward its target with an exponential ease (about 90ms), decaying between reports (about 550ms), with a 1.8 gain so it matches the frozen lines in scale. Each finished sentence freezes into the stack as that sentence's own loudness over time. Every frozen line is drawn at full height (peak 0.9), tapered to the baseline at both ends, with flat edges and a rise in the middle like the sleeve. Loudness between sentences is not shown. How much was said is carried by the number of lines.
- **Line count:** up to 16 lines on the main screen (spacing 12 to 24dp) and 6 on bands under 220dp (the cover screen). This departs from the contract's 12 to close an empty band between the caption and the plot. Lines that have no sentence yet draw as 1dp Faint Rule slots.
- **Ridge height:** the lift comes from the headroom above the back line (at least 3.2 line spacings), so the tallest peak reaches the top of the plot band.
- **Quiet state:** when the mic is open but nothing loud enough to count as speech has been heard for over 1.2s, or when recording has stopped, the live line draws dashed (6dp on, 6dp off) with a small drift. This state is implemented but has not been captured on a device. After a mic error, the plot becomes tappable with the description "Listen again".
- **Fold:** on Done, the stack collapses onto the front line over 350ms with a decelerate ease (factor 2.5). Empty slots fade out, the label changes to `015 filed`, and the screen closes. With animations off, the live line steps between 5 levels and there is no fold.

## Do's and Don'ts

### Do:
- **Do** draw every control as a 1.5dp Sleeve White hairline on Matte Black, and keep Done the only filled control.
- **Do** put status in the code strip, with each block 18 × 6dp, square, and spaced 4dp, and always pair it with words.
- **Do** set labels and catalog numbers in Barlow Condensed SemiBold tracked caps (0.28 for labels, 0.16 for buttons), and captions in Barlow sentence case.
- **Do** give the pulse plot all the flexible vertical space on a capture screen.
- **Do** keep a reduced-motion path for any animation: stepped levels, no authored transition.
- **Do** draw new icons as 24dp outline vectors with 1.5dp round-capped strokes.

### Don't:
- **Don't** use a code hue (yellow, blue, red) as an accent, button color, or text color.
- **Don't** add shadows, glows, blurs, or gradients; depth is Matte Black overlap only.
- **Don't** add a light theme or a night-mode variant; the black ground is the identity.
- **Don't** use glyph or icon-font icons, or fill icons in white.
- **Don't** put Faint Rule (ink-faint) on text; it is for dividers and empty plot slots only.
- **Don't** round the corners of rectangles; shapes are either full circles or sharp.
