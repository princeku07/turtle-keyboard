import '../shared/bridge';
import type { GameState } from '../shared/bridge';
import './style.css';

/**
 * Would You Rather game. Reads game shape + roster from `window.TurtleGame` and
 * drives a one-question-at-a-time quiz locally, writing the full answer array
 * once via writePlayerState when the user finishes.
 *
 * State machine (derived from `game.players` + my uid on every update):
 *   roster empty                 → quiz
 *   me in roster, partner not    → waiting
 *   both in roster               → results
 *   roster full, me not in       → game full (read-only landing)
 *
 * Players subcollection is rule-immutable (single write per uid), so a uid in
 * roster ALWAYS means that player has fully submitted — no half-states.
 */

interface WyrState {
  questions: { a: string; b: string }[];
}

interface WyrPlayerState {
  answers: ('A' | 'B')[];
}

interface Question {
  a: string;
  b: string;
}

interface LocalQuiz {
  answers: ('A' | 'B')[];
  currentIndex: number;
  submitting: boolean;
  submitError: string | null;
}

const app = document.getElementById('app')!;
let game: GameState | null = null;
let myUid: string | null = null;
const local: LocalQuiz = {
  answers: [],
  currentIndex: 0,
  submitting: false,
  submitError: null,
};

const bridge = window.TurtleGame;
if (!bridge || typeof bridge.subscribe !== 'function') {
  app.innerHTML = errorCard('Bridge missing — open this from the Turtle Keyboard app.');
} else if (bridge.type() && bridge.type() !== 'wyr') {
  app.innerHTML = errorCard('Wrong game type loaded.');
} else {
  myUid = bridge.uid();
  bridge.subscribe(onUpdate, onError);
}

function onUpdate(next: GameState): void {
  game = next;
  render();
}

function onError(reason: string): void {
  app.innerHTML = errorCard(messageForError(reason));
}

function render(): void {
  if (!game) {
    app.innerHTML = '<div class="card center"><h2>Loading…</h2></div>';
    return;
  }
  const wyrState = (game.state ?? {}) as Partial<WyrState>;
  if (!Array.isArray(wyrState.questions) || wyrState.questions.length < 2) {
    app.innerHTML = errorCard('This game looks malformed.');
    return;
  }
  const questions = wyrState.questions;
  const meInRoster = !!myUid && game.players.some(p => p.uid === myUid);
  const rosterFull = game.players.length >= 2;
  const bothDone =
    rosterFull && game.players.every(p => Array.isArray((p.state as WyrPlayerState | undefined)?.answers));

  if (meInRoster && bothDone) {
    app.innerHTML = renderResults(questions, game.players);
    return;
  }
  if (meInRoster) {
    app.innerHTML = renderWaiting(game.players);
    return;
  }
  if (rosterFull) {
    app.innerHTML = renderGameFull();
    return;
  }
  app.innerHTML = renderQuiz(questions);
  wireQuizHandlers();
}

function renderQuiz(questions: Question[]): string {
  if (local.submitting) {
    return '<div class="card center"><div class="pill muted">SUBMITTING</div><h2>Sending your answers…</h2></div>';
  }
  if (local.currentIndex >= questions.length) {
    // All answered locally — fire write and show progress.
    submit();
    return '<div class="card center"><div class="pill muted">SUBMITTING</div><h2>Sending your answers…</h2></div>';
  }
  const q = questions[local.currentIndex];
  return (
    '<div class="card">' +
    `<div class="pill">Q ${local.currentIndex + 1} / ${questions.length}</div>` +
    '<h2>Would you rather…</h2>' +
    `<button class="choice" data-choice="A">${escapeHtml(q.a)}</button>` +
    '<div class="or">or</div>' +
    `<button class="choice" data-choice="B">${escapeHtml(q.b)}</button>` +
    '</div>' +
    (local.submitError
      ? '<div class="card center"><div class="pill red">RETRY</div><p class="muted">' +
        escapeHtml(local.submitError) +
        '</p></div>'
      : '')
  );
}

function renderWaiting(players: GameState['players']): string {
  const partner = players.find(p => p.uid !== myUid);
  return (
    '<div class="card center">' +
    '<div class="pill">YOU&rsquo;RE DONE</div>' +
    '<h2>Waiting for partner…</h2>' +
    '<p class="muted">' +
    (partner ? 'Partner joined but hasn&rsquo;t submitted yet.' : 'Share the link to invite someone.') +
    '</p>' +
    '</div>'
  );
}

function renderResults(questions: Question[], players: GameState['players']): string {
  const p1 = players[0];
  const p2 = players[1];
  const ans1 = ((p1.state as WyrPlayerState | undefined)?.answers ?? []) as ('A' | 'B')[];
  const ans2 = ((p2.state as WyrPlayerState | undefined)?.answers ?? []) as ('A' | 'B')[];

  let matches = 0;
  for (let i = 0; i < questions.length; i++) if (ans1[i] === ans2[i]) matches++;

  const labelP1 = p1.uid === myUid ? 'You' : 'Player 1';
  const labelP2 = p2.uid === myUid ? 'You' : 'Player 2';

  let rows = '';
  for (let j = 0; j < questions.length; j++) {
    const a = ans1[j];
    const b = ans2[j];
    const match = a === b;
    rows +=
      '<div class="result-row">' +
      `<div class="result-q">${j + 1}. <strong>${escapeHtml(questions[j].a)}</strong>` +
      ` <em>vs</em> <strong>${escapeHtml(questions[j].b)}</strong></div>` +
      '<div class="result-picks">' +
      `<span class="badge p1">${a ?? '?'}</span>` +
      `<span class="badge p2">${b ?? '?'}</span>` +
      (match ? '<span class="match-tag">match</span>' : '') +
      '</div>' +
      '</div>';
  }

  return (
    '<div class="card">' +
    '<div class="pill match">RESULTS</div>' +
    `<h1>${matches} / ${questions.length} agree</h1>` +
    '<div class="legend">' +
    `<span><span class="dot p1"></span>${labelP1}</span>` +
    `<span><span class="dot p2"></span>${labelP2}</span>` +
    '</div>' +
    rows +
    '</div>'
  );
}

function renderGameFull(): string {
  return (
    '<div class="card center">' +
    '<div class="pill red">GAME FULL</div>' +
    '<h2>This game already has 2 players.</h2>' +
    '<p class="muted">Ask them to share the results, or start a new /wyr in Turtle.</p>' +
    '</div>'
  );
}

function errorCard(message: string): string {
  return (
    '<div class="card center">' +
    '<div class="pill red">ERROR</div>' +
    `<h2>${escapeHtml(message)}</h2>` +
    '</div>'
  );
}

function wireQuizHandlers(): void {
  document.querySelectorAll<HTMLButtonElement>('.choice').forEach(btn => {
    btn.addEventListener('click', () => pickChoice(btn.getAttribute('data-choice') as 'A' | 'B'));
  });
}

function pickChoice(letter: 'A' | 'B'): void {
  if (letter !== 'A' && letter !== 'B') return;
  local.submitError = null;
  local.answers.push(letter);
  local.currentIndex++;
  render();
}

function submit(): void {
  if (local.submitting) return;
  local.submitting = true;
  window.TurtleGame!.writePlayerState({ answers: local.answers.slice() })
    .then(() => {
      local.submitting = false;
      // Listener fires and transitions us to waiting/results automatically.
    })
    .catch((e: Error) => {
      local.submitting = false;
      const code = e?.message || 'unknown';
      if (code === 'already_joined') return; // race with another tab
      local.submitError = messageForError(code);
      local.answers = [];
      local.currentIndex = 0;
      render();
    });
}

function escapeHtml(s: string): string {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
}

function messageForError(reason: string): string {
  switch (reason) {
    case 'not_signed_in':
      return 'Open Turtle and sign in to play.';
    case 'game_not_found':
      return 'This game doesn&rsquo;t exist or has expired.';
    case 'permission_denied':
      return 'You don&rsquo;t have permission to view this game.';
    case 'network':
      return 'Network unavailable — check your connection.';
    default:
      return 'Something went wrong: ' + reason;
  }
}
