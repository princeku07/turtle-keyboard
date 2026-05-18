import '../shared/bridge';
import type { GameState } from '../shared/bridge';
import './style.css';

/**
 * Jigsaw puzzle. Each piece is an SVG `<path>` with interlocking tab/blank edges,
 * filled with a `<pattern>` that maps the user's image to the piece's ORIGINAL
 * slice. When pieces shuffle, the path geometry stays (the piece still looks like
 * its corner shape), but the pattern offset is recomputed so the slice rendered
 * is always the slice for the piece's original (row, col), wherever it currently
 * sits on the board.
 *
 * Edge generation is deterministic by (row, col) so the puzzle shape is stable
 * across page reloads and (eventually) across all clients viewing the same
 * puzzle. Per-puzzle randomization could seed by puzzleId later.
 *
 * State machine: `positions[cellIdx] = originalTileIdx`. Drag any piece onto
 * any other piece → swap. Win = positions[i] === i for all i.
 */

interface PuzzleSharedState {
  imageUrl?: string;
  gridSize?: number;
}

type Edge = 'flat' | 'tab' | 'blank';
interface PieceEdges {
  top: Edge;
  right: Edge;
  bottom: Edge;
  left: Edge;
}

// SVG coordinate system (independent of pixel size — viewBox scales to fit container).
const CELL = 100;
const TAB = 22;          // tab protrusion size in SVG units
const BOARD = (n: number) => n * CELL;

const app = document.getElementById('app')!;

let game: GameState | null = null;
let initialized = false;
let positions: number[] = [];
let gridSize = 0;
let imageUrl = '';
let solved = false;

interface Dragging {
  group: SVGGElement;
  cellIdx: number;
  /** Pointer-down position in CSS pixels — for the drag-threshold check (which
   *  is a SCREEN-space gesture, not a coordinate-system one). */
  startCssX: number;
  startCssY: number;
  /** Same pointer-down position projected into the SVG's user space. Drag
   *  translations are computed as (currentUser - startUser) so the piece
   *  tracks the finger 1:1 regardless of SVG scaling. */
  startUserX: number;
  startUserY: number;
  /** The group's pre-drag SVG `transform` attribute (the translate that puts
   *  it at its current cell). New drag translates are appended to this. */
  baseTransform: string;
  /** Snapshot of the SVG's screen→user matrix at drag start. The SVG doesn't
   *  resize during a drag, so this is cached once. */
  ctmInverse: DOMMatrix;
  /** Has the pointer moved far enough to count as a drag (vs. a tap)? Toggled
   *  to true on first pointermove that exceeds DRAG_THRESHOLD; if still false
   *  on pointerup, no swap happens — prevents accidental rearrangements from
   *  finger jitter when the user just meant to tap. */
  moved: boolean;
}
let drag: Dragging | null = null;
/** Threshold is in CSS pixels — a screen-space gesture concept, not user-space. */
const DRAG_THRESHOLD = 8;

// -- bootstrap ----------------------------------------------------------

const bridge = window.TurtleGame;
if (!bridge || typeof bridge.subscribe !== 'function') {
  app.innerHTML = errorCard('Bridge missing — open this from the Turtle Keyboard app.');
} else if (bridge.type() && bridge.type() !== 'puzzle') {
  app.innerHTML = errorCard('Wrong game type loaded.');
} else {
  bridge.subscribe(onUpdate, onError);
}

function onUpdate(next: GameState): void {
  game = next;
  const state = (game.state ?? {}) as PuzzleSharedState;
  if (!state.imageUrl || !state.gridSize) {
    app.innerHTML = errorCard('Puzzle is missing image or grid size.');
    return;
  }
  if (!initialized) {
    initialized = true;
    gridSize = state.gridSize;
    imageUrl = state.imageUrl;
    positions = shuffledOrder(gridSize * gridSize);
  }
  render();
}

function onError(reason: string): void {
  app.innerHTML = errorCard(messageForError(reason));
}

// -- render -------------------------------------------------------------

function render(): void {
  const html =
    '<div class="card">' +
      '<div class="pill">PUZZLE</div>' +
      `<h2>Solve together</h2>` +
      `<p class="muted">Drag pieces to swap. ${positions.length} pieces.</p>` +
      renderBoard() +
      (solved ? '<div class="win-banner">Solved 🎉</div>' : '') +
    '</div>';
  app.innerHTML = html;
  attachDragHandlers(document.getElementById('board') as unknown as SVGSVGElement);
}

function renderBoard(): string {
  const size = BOARD(gridSize);
  let defs = '';
  let pieces = '';

  for (let cellIdx = 0; cellIdx < positions.length; cellIdx++) {
    const tileId = positions[cellIdx];
    const curR = Math.floor(cellIdx / gridSize);
    const curC = cellIdx % gridSize;
    const origR = Math.floor(tileId / gridSize);
    const origC = tileId % gridSize;

    const edges = pieceEdges(origR, origC, gridSize);
    const path = piecePath(edges);

    // Pattern coords are in the PATH's user-space (post-<g>-transform), so
    // they're independent of the piece's current position. We want the image's
    // pixel (origC*CELL, origR*CELL) — the piece's slice — to land at the
    // cell's local top-left (TAB, TAB).
    //   patX = TAB - origC * CELL  (so image origin sits at piece-local that value)
    // When this is wrong (e.g. depends on curC/curR), pieces near the boundary
    // show wrong slices and adjacent pieces appear to "repeat" the same image.
    const patX = TAB - origC * CELL;
    const patY = TAB - origR * CELL;

    defs +=
      `<pattern id="pat-${cellIdx}" patternUnits="userSpaceOnUse"` +
      ` x="${patX}" y="${patY}" width="${size}" height="${size}">` +
      `<image href="${escapeAttr(imageUrl)}" x="0" y="0"` +
      ` width="${size}" height="${size}"` +
      ` preserveAspectRatio="xMidYMid slice"/>` +
      '</pattern>';

    // <g> translates so the piece's CELL top-left sits at (curC*CELL, curR*CELL).
    // Path draws in piece-local coords where the cell starts at (TAB, TAB) — so
    // subtract TAB from the group transform to align.
    const tx = curC * CELL - TAB;
    const ty = curR * CELL - TAB;
    pieces +=
      `<g class="piece" data-cell="${cellIdx}" transform="translate(${tx} ${ty})">` +
      // Invisible cell-sized hit target — pointer events fall through the path
      // (which has a wonky tab/blank shape that misses corners) but always
      // catch the rect. CELL × CELL exactly fills the cell with no overlap.
      `<rect class="hit" x="${TAB}" y="${TAB}" width="${CELL}" height="${CELL}"/>` +
      `<path d="${path}" fill="url(#pat-${cellIdx})"/>` +
      `</g>`;
  }

  // ViewBox 0..size (no extra buffer — boundary pieces have flat edges and don't
  // extend tabs outside the board).
  return (
    `<svg id="board" class="board" viewBox="0 0 ${size} ${size}"` +
    ` preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">` +
    `<defs>${defs}</defs>` +
    pieces +
    '</svg>'
  );
}

// -- piece geometry ------------------------------------------------------

/**
 * Edge type for one side of one piece. Two pieces sharing an edge must have
 * complementary types (tab ↔ blank) so they visually interlock. Boundary edges
 * are flat. Determined by a tiny hash on (row, col, direction) so the puzzle
 * shape is stable across reloads.
 */
function pieceEdges(row: number, col: number, n: number): PieceEdges {
  return {
    top:    row === 0     ? 'flat' : (hashEdge(row - 1, col,     'h') ? 'tab'   : 'blank'),
    bottom: row === n - 1 ? 'flat' : (hashEdge(row,     col,     'h') ? 'blank' : 'tab'),
    left:   col === 0     ? 'flat' : (hashEdge(row,     col - 1, 'v') ? 'tab'   : 'blank'),
    right:  col === n - 1 ? 'flat' : (hashEdge(row,     col,     'v') ? 'blank' : 'tab'),
  };
}

function hashEdge(r: number, c: number, dir: 'h' | 'v'): boolean {
  // Cheap deterministic hash. dir distinguishes horizontal vs vertical edges
  // between the same (r, c) pair.
  return (((r * 31 + c * 17 + (dir === 'h' ? 7 : 13)) >>> 0) % 2) === 0;
}

/**
 * Returns an SVG path-data string for a single piece. Path starts at the cell's
 * top-left (TAB, TAB) in piece-local coords, traces the four edges, and closes.
 * The piece's bounding box is (CELL + 2*TAB) on each side; the cell occupies
 * the inner (CELL × CELL) region inset by TAB.
 */
function piecePath(edges: PieceEdges): string {
  const x0 = TAB, y0 = TAB;
  const x1 = TAB + CELL, y1 = TAB + CELL;
  return (
    `M ${x0} ${y0}` +
    edgeSegment(edges.top,    x0, y0, x1, y0,  0, -1) +
    edgeSegment(edges.right,  x1, y0, x1, y1,  1,  0) +
    edgeSegment(edges.bottom, x1, y1, x0, y1,  0,  1) +
    edgeSegment(edges.left,   x0, y1, x0, y0, -1,  0) +
    ' Z'
  );
}

/**
 * One edge of a piece. (sx, sy)→(ex, ey) is the edge span in piece-local coords.
 * (outDx, outDy) is the outward perpendicular unit vector — for the top edge
 * outward is up (0, -1), etc. `tab` bumps outward, `blank` bumps inward.
 *
 * The bump uses TWO consecutive cubic Béziers — one for the "neck-in" approach
 * to the bump, one for the bump itself. Trades a bit of code for a noticeably
 * more circular bulge than a single quadratic gives.
 */
function edgeSegment(
  type: Edge,
  sx: number, sy: number,
  ex: number, ey: number,
  outDx: number, outDy: number,
): string {
  if (type === 'flat') return ` L ${ex} ${ey}`;

  // Anchor points: enter the bump at f1 of the way along, exit at f2.
  const f1 = 0.35;
  const f2 = 0.65;
  const p1x = sx + (ex - sx) * f1;
  const p1y = sy + (ey - sy) * f1;
  const p2x = sx + (ex - sx) * f2;
  const p2y = sy + (ey - sy) * f2;

  const dir = type === 'tab' ? 1 : -1;
  const bumpH = TAB * dir;       // signed bump height
  const bumpW = (ex - sx) * 0.20 + (ey - sy) * 0.20; // perpendicular wiggle width

  // Apex of the bump (between p1 and p2, offset outward).
  const apexX = (p1x + p2x) / 2 + outDx * bumpH;
  const apexY = (p1y + p2y) / 2 + outDy * bumpH;

  // Control points for the two cubics — pulled along the edge direction to
  // shape the neck-narrowing then widening that gives the classic jigsaw lobe.
  const c1x = p1x + outDx * bumpH * 1.4 - bumpW * 0.15;
  const c1y = p1y + outDy * bumpH * 1.4 - bumpW * 0.15;
  const c2x = apexX - (ex - sx) * 0.15;
  const c2y = apexY - (ey - sy) * 0.15;
  const c3x = apexX + (ex - sx) * 0.15;
  const c3y = apexY + (ey - sy) * 0.15;
  const c4x = p2x + outDx * bumpH * 1.4 + bumpW * 0.15;
  const c4y = p2y + outDy * bumpH * 1.4 + bumpW * 0.15;

  return (
    ` L ${p1x} ${p1y}` +
    ` C ${c1x} ${c1y}, ${c2x} ${c2y}, ${apexX} ${apexY}` +
    ` C ${c3x} ${c3y}, ${c4x} ${c4y}, ${p2x} ${p2y}` +
    ` L ${ex} ${ey}`
  );
}

// -- drag/drop ----------------------------------------------------------

function attachDragHandlers(board: SVGSVGElement): void {
  board.addEventListener('pointerdown', onPointerDown as EventListener);
  board.addEventListener('pointermove', onPointerMove as EventListener);
  board.addEventListener('pointerup', onPointerUp as EventListener);
  board.addEventListener('pointercancel', onPointerUp as EventListener);
}

function onPointerDown(e: PointerEvent): void {
  if (solved) return;
  const group = (e.target as Element | null)?.closest('.piece') as SVGGElement | null;
  if (!group) return;
  const cellIdx = Number((group as unknown as HTMLElement).dataset.cell);
  if (Number.isNaN(cellIdx)) return;

  const svg = group.ownerSVGElement;
  const ctm = svg?.getScreenCTM();
  if (!svg || !ctm) return;
  const ctmInverse = ctm.inverse();
  const startUser = clientToUserSpace(e.clientX, e.clientY, ctmInverse);

  drag = {
    group,
    cellIdx,
    startCssX: e.clientX,
    startCssY: e.clientY,
    startUserX: startUser.x,
    startUserY: startUser.y,
    baseTransform: group.getAttribute('transform') ?? '',
    ctmInverse,
    moved: false,
  };
  // NOTE: `held` class + reparent happen on first move past threshold (not
  // here). Premature visual feedback on tap-then-no-move feels jittery.
  e.preventDefault();
}

function onPointerMove(e: PointerEvent): void {
  if (!drag) return;

  // Threshold check uses CSS pixels — that's the screen gesture concept.
  if (!drag.moved) {
    const dxCss = e.clientX - drag.startCssX;
    const dyCss = e.clientY - drag.startCssY;
    if (Math.hypot(dxCss, dyCss) < DRAG_THRESHOLD) return;
    drag.moved = true;
    drag.group.classList.add('held');
    // Re-parent to end of SVG so the held piece renders on top (SVG paint
    // order = document order; no native z-index).
    drag.group.parentNode?.appendChild(drag.group);
  }

  // Position update uses USER-SPACE deltas so 1 px of finger movement → 1 px
  // of piece movement on screen, regardless of the SVG's viewBox-to-pixel
  // scale. CSS `transform: translate(Xpx)` on an SVG element is interpreted
  // in user units, so we set the SVG `transform` attribute directly to avoid
  // the px/user-unit ambiguity.
  const cur = clientToUserSpace(e.clientX, e.clientY, drag.ctmInverse);
  const dxUser = cur.x - drag.startUserX;
  const dyUser = cur.y - drag.startUserY;
  drag.group.setAttribute('transform',
    `${drag.baseTransform} translate(${dxUser} ${dyUser})`);

  document.querySelectorAll('.piece.target').forEach(t => t.classList.remove('target'));
  const under = elementUnderPointer(e.clientX, e.clientY, drag.group);
  if (under && under !== drag.group) under.classList.add('target');
}

function onPointerUp(e: PointerEvent): void {
  if (!drag) return;
  const d = drag;
  drag = null;

  // Restore the pre-drag SVG transform attribute.
  d.group.setAttribute('transform', d.baseTransform);
  d.group.classList.remove('held');
  document.querySelectorAll('.piece.target').forEach(t => t.classList.remove('target'));

  if (!d.moved) return;

  const target = elementUnderPointer(e.clientX, e.clientY, d.group);
  if (!target || target === d.group) return;
  const targetIdx = Number((target as unknown as HTMLElement).dataset.cell);
  if (Number.isNaN(targetIdx) || targetIdx === d.cellIdx) return;

  [positions[d.cellIdx], positions[targetIdx]] = [positions[targetIdx], positions[d.cellIdx]];
  checkWin();
  render();
}

/** Apply an inverse screen CTM to a CSS-pixel point → SVG user-space point. */
function clientToUserSpace(
  cssX: number, cssY: number, inverse: DOMMatrix,
): { x: number; y: number } {
  const pt = new DOMPoint(cssX, cssY).matrixTransform(inverse);
  return { x: pt.x, y: pt.y };
}

/** elementFromPoint with the dragged piece's pointer-events suppressed so we
 *  detect the piece UNDER it, not itself. */
function elementUnderPointer(x: number, y: number, dragged: SVGGElement): SVGGElement | null {
  const prev = (dragged as unknown as HTMLElement).style.pointerEvents;
  (dragged as unknown as HTMLElement).style.pointerEvents = 'none';
  const el = document.elementFromPoint(x, y) as Element | null;
  (dragged as unknown as HTMLElement).style.pointerEvents = prev;
  if (!el) return null;
  return el.closest('.piece') as SVGGElement | null;
}

// -- win state ----------------------------------------------------------

function checkWin(): void {
  if (solved) return;
  for (let i = 0; i < positions.length; i++) {
    if (positions[i] !== i) return;
  }
  solved = true;
  setTimeout(() => {
    document.querySelectorAll('.piece').forEach(p => p.classList.add('solved-flash'));
  }, 0);
}

// -- helpers ------------------------------------------------------------

function shuffledOrder(n: number): number[] {
  const arr = Array.from({ length: n }, (_, i) => i);
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  if (arr.every((v, i) => v === i)) {
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [arr[i], arr[j]] = [arr[j], arr[i]];
    }
  }
  return arr;
}

function errorCard(message: string): string {
  return (
    '<div class="card center">' +
    '<div class="pill red">ERROR</div>' +
    `<h2>${escapeHtml(message)}</h2>` +
    '</div>'
  );
}

function messageForError(reason: string): string {
  switch (reason) {
    case 'not_signed_in':     return 'Open Turtle and sign in to play.';
    case 'game_not_found':    return 'This puzzle doesn&rsquo;t exist or has expired.';
    case 'permission_denied': return 'You don&rsquo;t have permission to view this puzzle.';
    case 'network':           return 'Network unavailable — check your connection.';
    default:                  return 'Something went wrong: ' + reason;
  }
}

function escapeHtml(s: string): string {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
}

function escapeAttr(s: string): string {
  return String(s ?? '').replace(/[&<>"]/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!,
  );
}
