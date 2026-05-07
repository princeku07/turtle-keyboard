export type CommandId =
  | "/cap"
  | "/sticker"
  | "/edit"
  | "/avatar"
  | "/scene"
  | "/meme"
  | "/split"
  | "/notion"
  | "/slack";

export type Accent = "pink" | "lime" | "blue" | "orange" | "ink";

export type CommandKind = "image" | "split" | "send";

export type CommandContent = {
  cmd: CommandId;
  hint: string;
  accent: Accent;
  kind: CommandKind;
  bg: string;
  fg: string;
  sendTarget?: string;
  splitSample?: { total: string; people: number; each: string };
  resultLabel?: string;

  hero: {
    typed: string;
    headline: string;
    subhead: string;
    placeholder: string;
  };

  cta: {
    lead: string;
    hi: string;
    tail: string;
  };

  problem: {
    eyebrow: string;
    headlineLead: string;
    headlineHi: string;
    headlineTail: string;
    body: string;
    fix: string;
    oldSteps: string[];
  };

  useCases: Array<{
    title: string;
    example: string;
    emoji: string;
  }>;

  mock: {
    incoming: string;
    typedExample: string;
    resultEmoji: string;
    resultGradient: string;
    reaction: string;
    insight: string;
    spotlightHeadlineLead: string;
    spotlightHeadlineHi: string;
    spotlightHeadlineTail: string;
  };
};

export const COMMANDS: Record<CommandId, CommandContent> = {
  "/cap": {
    cmd: "/cap",
    hint: "an image, any image",
    accent: "pink",
    kind: "image",
    bg: "bg-pink",
    fg: "text-cream",
    hero: {
      typed: "a samurai cat",
      headline: "an image, straight in your chat.",
      subhead: "Type /cap and any idea. We make the picture, drop it in your composer. No app switching.",
      placeholder: "type your image prompt",
    },
    cta: { lead: "One slash. ", hi: "Any image", tail: ". In your chat." },
    problem: {
      eyebrow: "§ /cap · the problem",
      headlineLead: "Seven steps to drop ",
      headlineHi: "one image",
      headlineTail: " in the chat. The moment's already gone.",
      body: "Stop. Open Midjourney or ChatGPT. Type the prompt. Wait. Long-press. Save. Switch back. Attach. By then the group chat has moved on three messages.",
      fix: "with /cap: type your prompt · paste · done.",
      oldSteps: ["stop", "open mj", "prompt", "wait", "save", "switch", "attach"],
    },
    useCases: [
      { title: "group-chat reactions", example: "/cap a golden retriever as a samurai", emoji: "🐕⚔️" },
      { title: "birthday cards from a slash", example: "/cap birthday card for jared", emoji: "🎂" },
      { title: "tinder opener no one's seen", example: "/cap monday mood as a renaissance painting", emoji: "🎨" },
      { title: "subtle flex in the dm", example: "/cap my cat but cyberpunk", emoji: "🐈‍⬛" },
      { title: "moodboard one-offs", example: "/cap moody polaroid of a rainy window", emoji: "📸" },
      { title: "pixel-art everything", example: "/cap pixel-art coffee cup", emoji: "👾" },
    ],
    mock: {
      incoming: "jared just sent a samurai meme 😭",
      typedExample: "/cap a golden retriever as a samurai",
      resultEmoji: "🐕‍🦺⚔️",
      resultGradient: "from-orange via-pink to-blue",
      reaction: "😂😂 send it everywhere",
      insight: "The group chat is moving. You type /cap a samurai cat. The image lands on your clipboard. Paste. Send. Status acquired.",
      spotlightHeadlineLead: "The moment doesn't ",
      spotlightHeadlineHi: "wait",
      spotlightHeadlineTail: " for a context switch.",
    },
  },

  "/sticker": {
    cmd: "/sticker",
    hint: "transparent-bg, ready to send",
    accent: "lime",
    kind: "image",
    bg: "bg-lime",
    fg: "text-cream",
    hero: {
      typed: "a turtle saying 'on my way'",
      headline: "the sticker your group chat is missing.",
      subhead: "Type /sticker — get a transparent-bg cutout, sized for iMessage and WhatsApp packs. No more screenshotting other people's art.",
      placeholder: "type your sticker idea",
    },
    cta: { lead: "One slash. ", hi: "Any sticker", tail: ". In your pack." },
    problem: {
      eyebrow: "§ /sticker · the problem",
      headlineLead: "Every group chat has ",
      headlineHi: "an inside joke",
      headlineTail: " no sticker pack on Earth covers.",
      body: "You've imported nine third-party packs. None of them say what your friends actually say. The one perfect reaction lives only in your head.",
      fix: "with /sticker: describe it · cutout in the composer · paste.",
      oldSteps: ["search packs", "install", "search", "install", "wrong vibe", "uninstall", "give up"],
    },
    useCases: [
      { title: "inside-joke stickers", example: "/sticker a turtle saying 'on my way' (sarcastic)", emoji: "🐢" },
      { title: "your dog as a sticker", example: "/sticker my golden retriever wearing sunglasses", emoji: "🕶️" },
      { title: "reactions no pack has", example: "/sticker someone visibly touching grass", emoji: "🌱" },
      { title: "couple-name custom", example: "/sticker 'team jared' varsity patch", emoji: "🏆" },
      { title: "team / group identity", example: "/sticker our pickleball squad logo, retro", emoji: "🥒" },
      { title: "seasonal one-offs", example: "/sticker a pumpkin saying 'spooky szn'", emoji: "🎃" },
    ],
    mock: {
      incoming: "we need a sticker for when sam is late again",
      typedExample: "/sticker a turtle holding a sign 'on my way (lying)'",
      resultEmoji: "🐢",
      resultGradient: "from-lime via-cream to-orange",
      reaction: "PIN THIS. SEND TO THE GROUP.",
      insight: "Stickers are hyper-personal — every chat needs different ones, and pre-made packs can't keep up. /sticker turns the vibe in your head into a transparent-bg cutout, ready to paste. The pack is just you and your friends now.",
      spotlightHeadlineLead: "Stickers built for ",
      spotlightHeadlineHi: "this chat",
      spotlightHeadlineTail: ". Not a million strangers.",
    },
  },

  "/edit": {
    cmd: "/edit",
    hint: "drop image, describe change",
    accent: "blue",
    kind: "image",
    bg: "bg-blue",
    fg: "text-cream",
    hero: {
      typed: "remove the people in the background",
      headline: "edit any image without opening Photoshop.",
      subhead: "Drop an image, type /edit + what you want changed. The keyboard inpaints in place — no app switch, no Photoshop tax.",
      placeholder: "describe the edit",
    },
    cta: { lead: "One slash. ", hi: "Any edit", tail: ". No Photoshop." },
    problem: {
      eyebrow: "§ /edit · the problem",
      headlineLead: "Every photo is ",
      headlineHi: "almost perfect",
      headlineTail: ". One thing in the background ruins it.",
      body: "The only fix today: send to your laptop, learn Photoshop, lasso the thing, fill, undo, redo. Or accept the photo as-is. Most people accept.",
      fix: "with /edit: long-press the image · /edit remove the trash can · done.",
      oldSteps: ["airdrop", "open ps", "lasso", "fill", "undo", "export", "airdrop back"],
    },
    useCases: [
      { title: "remove photo-bombers", example: "/edit remove the people in the background", emoji: "🚷" },
      { title: "swap the sky", example: "/edit make the sky a sunset", emoji: "🌅" },
      { title: "fix a stray hand", example: "/edit remove the hand on the left", emoji: "✋" },
      { title: "change an outfit", example: "/edit put me in a black hoodie instead", emoji: "🧥" },
      { title: "clean up a screenshot", example: "/edit blur the names in this screenshot", emoji: "🫥" },
      { title: "color-correct a moment", example: "/edit warmer light, golden-hour vibe", emoji: "🌞" },
    ],
    mock: {
      incoming: "love the pic but the trash can is so distracting lol",
      typedExample: "/edit remove the trash can on the left",
      resultEmoji: "🪄",
      resultGradient: "from-blue via-pink to-cream",
      reaction: "WAIT how did you do that so fast",
      insight: "Photo editing used to mean a 4-app pipeline. /edit collapses it into a single line typed in the chat where the photo already lives. The edit lands as a new image, ready to send.",
      spotlightHeadlineLead: "Edit the photo where ",
      spotlightHeadlineHi: "you already are",
      spotlightHeadlineTail: ". The chat.",
    },
  },

  "/avatar": {
    cmd: "/avatar",
    hint: "your selfie, restyled",
    accent: "orange",
    kind: "image",
    bg: "bg-orange",
    fg: "text-ink",
    hero: {
      typed: "anime, then oil paint, then 3D",
      headline: "your face, every art style ever invented.",
      subhead: "Drop a selfie, type /avatar + a style. Get an anime version, an oil painting, a pixel sprite, a 3D render — usable as a profile pic, no extra app.",
      placeholder: "pick an art style",
    },
    cta: { lead: "One slash. ", hi: "Any aesthetic", tail: ". Your face." },
    problem: {
      eyebrow: "§ /avatar · the problem",
      headlineLead: "Every profile pic is ",
      headlineHi: "the same selfie",
      headlineTail: " from 2022. Updating it is a project.",
      body: "Lensa charges $8 per pack. Custom anime portraits are a $30 Fiverr gig and a 3-day wait. Most people give up and reuse the old one for another year.",
      fix: "with /avatar: drop selfie · /avatar anime · paste to bio.",
      oldSteps: ["lensa $8", "wait 20 min", "hate results", "fiverr $30", "wait 3 days", "give up", "reuse 2022 pic"],
    },
    useCases: [
      { title: "anime profile pic", example: "/avatar studio ghibli style", emoji: "🌸" },
      { title: "oil-painting linkedin", example: "/avatar dramatic oil portrait, baroque", emoji: "🖼️" },
      { title: "pixel-art for discord", example: "/avatar 16-bit pixel sprite", emoji: "👾" },
      { title: "3D pixar-style", example: "/avatar pixar-style 3D render", emoji: "🎬" },
      { title: "vintage polaroid", example: "/avatar 70s polaroid, soft grain", emoji: "📷" },
      { title: "y2k chrome", example: "/avatar y2k chrome, holographic", emoji: "💿" },
    ],
    mock: {
      incoming: "your pfp is so 2022 update it pls",
      typedExample: "/avatar studio ghibli style",
      resultEmoji: "🌸",
      resultGradient: "from-orange via-pink to-cream",
      reaction: "OK now do oil painting next",
      insight: "Updating a profile pic shouldn't be a $30, 3-day project. /avatar restyles your face in any aesthetic — in the chat, ready to post. Try ten styles in the time it takes to download Lensa.",
      spotlightHeadlineLead: "Try ten styles. ",
      spotlightHeadlineHi: "Not a $30 Fiverr gig",
      spotlightHeadlineTail: ".",
    },
  },

  "/scene": {
    cmd: "/scene",
    hint: "subject + setting, composed",
    accent: "ink",
    kind: "image",
    bg: "bg-ink",
    fg: "text-cream",
    hero: {
      typed: "my dog in a wes anderson hotel",
      headline: "compose any subject into any setting.",
      subhead: "Type /scene + subject + setting. The keyboard composes them into one staged image — your dog at a Wes Anderson hotel, your friend on the cover of Vogue.",
      placeholder: "type subject + setting",
    },
    cta: { lead: "One slash. ", hi: "Any scene", tail: ". Staged." },
    problem: {
      eyebrow: "§ /scene · the problem",
      headlineLead: "You can describe the picture in your head — ",
      headlineHi: "but you can't make it",
      headlineTail: ".",
      body: "Photoshop comps take hours. Mood boards are pinning other people's photos. The exact composition you want — your subject in that exact setting — basically doesn't exist on the open web.",
      fix: "with /scene: subject + setting · one staged image · paste.",
      oldSteps: ["pinterest", "search", "save", "comp in ps", "mask", "blend", "give up"],
    },
    useCases: [
      { title: "your pet, anywhere", example: "/scene my dog in a wes anderson hotel lobby", emoji: "🏨" },
      { title: "your friend on a magazine", example: "/scene sam on the cover of vogue, june issue", emoji: "📖" },
      { title: "team in a movie poster", example: "/scene our startup as an 80s heist movie poster", emoji: "🎞️" },
      { title: "fantasy travel snap", example: "/scene me at the top of a floating mountain", emoji: "🏔️" },
      { title: "concept-art moments", example: "/scene a coffee shop on mars at sunset", emoji: "☕" },
      { title: "kid's bedtime stories", example: "/scene a dragon learning to ride a bike", emoji: "🐉" },
    ],
    mock: {
      incoming: "imagine if our dog was a wes anderson character",
      typedExample: "/scene my golden retriever in a wes anderson hotel lobby",
      resultEmoji: "🏨",
      resultGradient: "from-ink via-pink to-orange",
      reaction: "I would die for this dog",
      insight: "/scene is for the picture that exists only in your head — your subject, your setting, composed exactly. No Pinterest detours. No mask-and-blend Photoshop dance. Just describe it, get it, paste it.",
      spotlightHeadlineLead: "The picture in your ",
      spotlightHeadlineHi: "head",
      spotlightHeadlineTail: ", staged from your chat.",
    },
  },

  "/meme": {
    cmd: "/meme",
    hint: "template + AI caption",
    accent: "pink",
    kind: "image",
    bg: "bg-pink",
    fg: "text-cream",
    hero: {
      typed: "about my code reviews",
      headline: "a meme template + caption, ready to send.",
      subhead: "Type /meme + the topic. The keyboard picks the right template, writes the caption, drops the finished meme in your composer.",
      placeholder: "type the meme topic",
    },
    cta: { lead: "One slash. ", hi: "The meme", tail: ". Captioned." },
    problem: {
      eyebrow: "§ /meme · the problem",
      headlineLead: "You know the ",
      headlineHi: "exact meme",
      headlineTail: ". You can't remember the template's name.",
      body: "imgflip → search 'distracted boyfriend' → wrong template → back → top text → bottom text → save → switch → attach. The joke had a 4-second window. You missed it.",
      fix: "with /meme: type the topic · finished meme · paste.",
      oldSteps: ["imgflip", "search", "wrong template", "type captions", "preview", "save", "switch"],
    },
    useCases: [
      { title: "reply memes, no template hunt", example: "/meme drake style: code reviews", emoji: "🎤" },
      { title: "team slack content", example: "/meme distracted boyfriend: me, prod, the bug", emoji: "🤦" },
      { title: "topical memes", example: "/meme this fits the 'is this a pigeon' template", emoji: "🦋" },
      { title: "couple-chat memes", example: "/meme spongebob mocking: 'i'll do the dishes later'", emoji: "🧽" },
      { title: "fast roast", example: "/meme galaxy brain: about my friend's pickleball obsession", emoji: "🧠" },
      { title: "self-deprecating", example: "/meme this is fine: about my deadline", emoji: "🔥" },
    ],
    mock: {
      incoming: "the way prod just exploded again 💀",
      typedExample: "/meme this is fine: about prod on a friday",
      resultEmoji: "🔥",
      resultGradient: "from-pink via-orange to-cream",
      reaction: "okay this is going in the team channel",
      insight: "Meme-making used to be a 7-step detour through imgflip. /meme picks the template, writes the caption, hands you the finished image. The joke lands in the original 4-second window.",
      spotlightHeadlineLead: "The joke lands ",
      spotlightHeadlineHi: "before it gets cold",
      spotlightHeadlineTail: ".",
    },
  },

  "/split": {
    cmd: "/split",
    hint: "split a bill, in any payment app",
    accent: "lime",
    kind: "split",
    bg: "bg-lime",
    fg: "text-cream",
    splitSample: { total: "₹1,500", people: 3, each: "₹500" },
    resultLabel: "split panel",
    hero: {
      typed: "1500",
      headline: "split a bill, right inside the keyboard.",
      subhead: "Type /split + an amount inside any payment app — the split view pops open above the keys. Pick how many people, the math is done. History saved to your own Google Sheet.",
      placeholder: "type the amount",
    },
    cta: { lead: "One slash. ", hi: "Bill split", tail: ". In your sheet." },
    problem: {
      eyebrow: "§ /split · the problem",
      headlineLead: "You're paying the bill. Splitting it means leaving the app for ",
      headlineHi: "another one",
      headlineTail: ".",
      body: "Leave the payment app. Open the splitter. Add everyone. Re-type the amount. Switch back to send. The side trip is short — but it's there every single time, so most days you skip the logging and hope everyone just remembers.",
      fix: "with /split: type the amount · the split view opens right above the keys.",
      oldSteps: ["leave app", "open splitter", "add people", "re-type amount", "switch back", "send", "forget"],
    },
    useCases: [
      { title: "dinner the moment the bill arrives", example: "/split 1500 with 3", emoji: "🍝" },
      { title: "rent on the 1st without a doc", example: "/split 36000 with 3 (rent)", emoji: "🏠" },
      { title: "diwali / birthday gift pool", example: "/split 4000 with 7 (sam's gift)", emoji: "🎁" },
      { title: "group cab back from the airport", example: "/split 800 with 4 (cab)", emoji: "🚖" },
      { title: "shared subscription each month", example: "/split 1099 with 3 (netflix)", emoji: "📺" },
      { title: "trip kitty with running totals", example: "/splits → goa-may", emoji: "🏖️" },
    ],
    mock: {
      incoming: "the bill is 1500. split it 3 ways?",
      typedExample: "/split 1500",
      resultEmoji: "₹500",
      resultGradient: "from-lime via-cream to-orange",
      reaction: "transferred ✓",
      insight: "/split lives in the apps where the money actually moves. Type the amount in your payment app, the split view opens right above the keys — no jumping out to a separate splitter. Pick the people, the math + history is saved to your own Google Sheet — not our server.",
      spotlightHeadlineLead: "Your money, your ",
      spotlightHeadlineHi: "spreadsheet",
      spotlightHeadlineTail: ". The keyboard just does the math.",
    },
  },

  "/notion": {
    cmd: "/notion",
    hint: "send any text to your notion",
    accent: "ink",
    kind: "send",
    bg: "bg-ink",
    fg: "text-cream",
    sendTarget: "Notion",
    resultLabel: "notion · created",
    hero: {
      typed: "save this article to my reading list",
      headline: "send anything to Notion in one slash.",
      subhead: "Type /notion + anything you'd save later. The keyboard structures it with AI, creates a page in your workspace. Tap the notification to open it.",
      placeholder: "type what to save",
    },
    cta: { lead: "One slash. ", hi: "Captured", tail: ". In Notion." },
    problem: {
      eyebrow: "§ /notion · the problem",
      headlineLead: "Half the things worth ",
      headlineHi: "remembering",
      headlineTail: " never make it into Notion. Switching apps is the tax.",
      body: "You see something good in WhatsApp. To save it: long-press, copy, switch to Notion, find the right page, paste, format, save, switch back. Eight steps. So it stays in WhatsApp forever.",
      fix: "with /notion: type the thing · ✓ page created · keep going.",
      oldSteps: ["copy", "switch", "find page", "paste", "format", "save", "switch back"],
    },
    useCases: [
      { title: "save the article you just read", example: "/notion add this to reading list (link)", emoji: "📰" },
      { title: "capture an idea mid-walk", example: "/notion idea: weekly habit recap email", emoji: "💡" },
      { title: "meeting notes without an app switch", example: "/notion standup notes for today", emoji: "📝" },
      { title: "log a recipe from a chat", example: "/notion save this dal recipe to cooking db", emoji: "🍲" },
      { title: "drop a link into your todo db", example: "/notion task: review PR #482, due fri", emoji: "✅" },
      { title: "journal what just happened", example: "/notion journal: today felt long because…", emoji: "📓" },
    ],
    mock: {
      incoming: "this article is gold — save it somewhere you'll actually see",
      typedExample: "/notion save this to reading list (link)",
      resultEmoji: "📒",
      resultGradient: "from-ink via-blue to-cream",
      reaction: "did u tag it correctly lol",
      insight: "/notion turns any text field into a one-line capture surface. The LLM picks the right page or database, structures the content, and creates the entry. You stay in WhatsApp; the page shows up in Notion.",
      spotlightHeadlineLead: "Capture the thought ",
      spotlightHeadlineHi: "where it happened",
      spotlightHeadlineTail: ". The page lands in Notion.",
    },
  },

  "/slack": {
    cmd: "/slack",
    hint: "send to slack from any app",
    accent: "blue",
    kind: "send",
    bg: "bg-blue",
    fg: "text-cream",
    sendTarget: "Slack",
    resultLabel: "slack · sent",
    hero: {
      typed: "#engineering prod is back ✓",
      headline: "ping Slack without opening Slack.",
      subhead: "Type /slack + your message in any app. Lands in your default channel — or use #channel to route. Comes back as a notification with the permalink.",
      placeholder: "#channel + your message",
    },
    cta: { lead: "One slash. ", hi: "Sent", tail: ". To Slack." },
    problem: {
      eyebrow: "§ /slack · the problem",
      headlineLead: "Five context switches to drop ",
      headlineHi: "one Slack message",
      headlineTail: ". Most days you don't bother.",
      body: "Open Slack, find the workspace, find the channel, type the line, send, switch back. By the time you're done you forgot why you opened the chat you were in.",
      fix: "with /slack: type · sent · permalink notification.",
      oldSteps: ["open slack", "workspace", "find channel", "type", "send", "switch back"],
    },
    useCases: [
      { title: "standup from the kitchen", example: "/slack #standup did x, doing y, blocked on z", emoji: "☕" },
      { title: "react in #random from anywhere", example: "/slack #random this is the funniest thing today", emoji: "😂" },
      { title: "forward the link to your team", example: "/slack #engineering check this article", emoji: "🔗" },
      { title: "DM a teammate while in transit", example: "/slack @sam can you cover the 3pm?", emoji: "💬" },
      { title: "status update without leaving your app", example: "/slack #ops PR up, requesting review", emoji: "🟢" },
      { title: "ack a message from a notification", example: "/slack 👀", emoji: "✅" },
    ],
    mock: {
      incoming: "tell #engineering prod is back",
      typedExample: "/slack #engineering prod is back ✓",
      resultEmoji: "🟢",
      resultGradient: "from-blue via-cream to-pink",
      reaction: "👏 nice — link in the channel",
      insight: "/slack turns any text field into a Slack composer. Use #channel to route, @user for DMs. The keyboard sends in the background, and the system notification comes back with the permalink so you can confirm it landed.",
      spotlightHeadlineLead: "Slack messages without ",
      spotlightHeadlineHi: "five context switches",
      spotlightHeadlineTail: ".",
    },
  },
};

export const COMMAND_ORDER: CommandId[] = [
  "/cap",
  "/split",
  "/notion",
  "/slack",
  "/sticker",
  "/edit",
  "/avatar",
  "/scene",
  "/meme",
];
