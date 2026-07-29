# Blog thumbnail image prompts — Turtle Logbook

All thumbnails share **one collage style** so the blog reads as a single set.
Save each generated image to **`public/blog/<slug>.jpg`** (filename = the post's
slug, exactly as listed). Recommended size **3:2 landscape, ~1536×1024**. The
grid crops center; keep the subject roughly centered. Until a file is added, the
card shows a soft gradient + faint turtle watermark, so you can drop them in one
at a time.

---

## The shared STYLE block — paste this in front of every subject prompt

> Editorial magazine **collage illustration**, mixed-media. A **black-and-white
> photographic cut-out** of the subject (with a subtle white paper "sticker"
> edge) placed over a **flat, vector-illustrated background**. Background is made
> of soft pastel color blocks and organic blob shapes in **periwinkle #5C6CC0,
> mauve #9069C4, aqua #4F9DB6, soft pink #F3E0E8, warm off-white #F7F7F9**.
> Add sparse **hand-drawn doodles**: sparkles, dotted connector lines, little
> stars, squiggles, tiny leaves. Light paper-grain texture. Playful, modern,
> calm, generous negative space, balanced composition. **3:2 landscape. No text,
> no words, no logos, no watermark, no UI screenshots.**

Then append **one** of the subjects below.

---

## Subjects (filename → prompt)

**`what-is-an-ai-keyboard.jpg`**
A B&W cut-out of a hand holding a smartphone; from the phone's screen a glowing
periwinkle **"/" slash mark** rises. Floating doodle sparkles around it. Aqua +
periwinkle background blobs.

**`build-a-keyboard-command-with-mcp.jpg`**
A B&W cut-out of two hands typing on a phone; thin doodle **connector lines with
little plug/node ends** fan out from the phone to small floating rounded app
tiles. Mauve + periwinkle background.

**`on-device-ai-vs-cloud-keyboards.jpg`**
A B&W cut-out of a smartphone tucked inside a **periwinkle shield outline**; a
small **cloud shape crossed out** with a doodle X floats to one side. Aqua +
lavender background, lock doodles.

**`create-a-live-poll-in-any-chat-app.jpg`**
A B&W cut-out of two hands each holding a phone (one iPhone-ish, one
Android-ish) facing each other; between them a floating **poll card** drawn as
simple bars with checkmark doodles. Pink + periwinkle background.

**`slash-commands-from-irc-to-your-keyboard.jpg`**
A retro-to-modern collage: a B&W cut-out of a **vintage computer / terminal**
beside a modern smartphone, a big doodle **"/"** bridging them. Mauve + aqua
background, star doodles.

**`local-llms-in-an-ios-keyboard.jpg`**
A B&W cut-out of a smartphone with a tiny **brain-or-chip** glowing inside it,
framed by a thin doodle **cage/box outline** (the memory limit). Periwinkle +
aqua background, spark doodles.

**`bringing-mcp-to-mobile-keyboards.jpg`**
A B&W cut-out of a **keyboard** at the center acting as a hub, with doodle lines
radiating out to small rounded tool tiles arranged in a ring. Periwinkle + mauve
background.

**`the-case-for-cross-platform-native.jpg`**
A B&W cut-out of **two hands building/knitting** something together (one gesture
Apple-ish, one Android-ish) meeting in the middle. Split aqua / mauve
background, dotted seam doodle down the center.

**`why-your-keyboard-shouldnt-talk-to-the-cloud.jpg`**
A B&W cut-out of a person cupping a hand to whisper toward a phone that sits
inside a **turtle-shell / dome outline**; a crossed-out cloud doodle floats
away. Lavender + periwinkle background.

**`zero-server-costs-local-inference.jpg`**
A B&W cut-out of a hand holding a phone; a doodle **receipt / price tag turning
into a big "0"**, tiny coin doodles dissolving into sparkles. Aqua + pink
background.

**`building-calm-ai.jpg`**
A calm, minimal collage: a B&W cut-out of a **hand resting beside a kettle** (or
a serene cut-out of a person breathing), very few doodles, lots of negative
space. Soft off-white + faint periwinkle background. The quietest image of the
set.

**`how-to-create-a-poll-in-whatsapp.jpg`**
A B&W cut-out of a thumb tapping a phone; a floating **poll card** with two bar
options and a checkmark doodle. Green-leaning but keep to the palette — lean on
aqua + soft pink blobs.

**`slack-slash-commands-everywhere.jpg`**
A B&W cut-out of a hand **carrying a big doodle "/"** from a desktop monitor
toward a phone; small rounded app tiles trail behind on a dotted path. Mauve +
periwinkle background.

**`the-end-of-the-calendar-app-shuffle.jpg`**
A B&W cut-out of hands **juggling a phone and a small calendar/date block**;
doodle loop-arrows and sparkles suggest the back-and-forth. Periwinkle + pink
background.

**`imessage-polls-not-working.jpg`**
A B&W cut-out of a slightly **puzzled person looking at a phone**; a doodle poll
card with a small "broken/❓" mark; one blue and one green speech-bubble doodle
hint at iPhone vs Android. Pink + aqua background.

**`ios-custom-keyboard-extension-tutorial.jpg`**
A B&W cut-out of hands **assembling a keyboard from parts / key caps**, with
thin **blueprint-style doodle lines** and measurement ticks. Aqua + periwinkle
background.

**`clevertype-alternatives.jpg`**
A B&W cut-out of a phone showing a keyboard, flanked by a doodle **comparison
grid of small rounded tiles** with tiny check/cross doodles. Mauve + aqua
background.

**`grammarly-keyboard-alternatives-iphone.jpg`**
A B&W cut-out of a hand **editing text on a phone**; doodle underlines,
checkmarks and a little correction squiggle float around. Periwinkle + pink
background.

---

### Tips
- Keep the **B&W subject** the clear focal point; the color lives in the flat
  background and doodles — that contrast is what makes the set feel cohesive.
- If your generator adds text, add `--no text` / "no lettering" to the prompt.
- After generating, optional size/format tidy-up:
  `sips -s format jpeg -Z 1536 input.png --out public/blog/<slug>.jpg`
- Filenames must match the slugs exactly, or the card keeps its placeholder.
