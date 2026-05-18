/**
 * Cross-platform game bridge shim. Side-effect import from each game's entry
 * (`import './shared/bridge';`). Wraps the platform-specific native interface
 * into a single `window.TurtleGame` API.
 *
 * - Android: `window.TurtleGame_native` is injected before page load via
 *   {@code WebView.addJavascriptInterface}. Methods are synchronous.
 * - iOS (when it lands): `window.webkit.messageHandlers.turtleGame.postMessage`
 *   for fire-and-forget; pre-injected `window.__TurtleGame_initial` for sync
 *   reads (uid, type, artifactId).
 * - Browser (dev): stubs that throw on writes, return empty on reads.
 *
 * Embedded model: each game's HTML is loaded from
 * {@code file:///android_asset/games/<name>/index.html}. The artifact id can
 * no longer be passed as a URL query string (file:// + query is fiddly), so
 * the native side exposes it via {@link TurtleGameApi#artifactId} — game JS
 * reads it from the bridge instead of `location.search`.
 */

declare global {
  interface Window {
    TurtleGame?: TurtleGameApi;
    TurtleGame_native?: AndroidNativeBridge;
    webkit?: {
      messageHandlers?: {
        turtleGame?: { postMessage(msg: unknown): void };
      };
    };
    __TurtleGame_initial?: {
      uid?: string;
      type?: string;
      artifactId?: string;
    };
  }
}

interface AndroidNativeBridge {
  uid(): string | null;
  type(): string;
  artifactId(): string;
  subscribe(): void;
  unsubscribe(): void;
  writePlayerState(writeId: string, json: string): void;
}

export interface TurtleGameApi {
  platform: 'android' | 'ios' | 'browser';
  uid(): string | null;
  type(): string;
  artifactId(): string;
  subscribe(
    onUpdate: (state: GameState) => void,
    onError: (reason: string) => void,
  ): void;
  unsubscribe(): void;
  writePlayerState(payload: unknown): Promise<void>;
  /** Native-invoked. Do NOT call from game code. */
  _onUpdate(jsonString: string): void;
  _onError(reason: string): void;
  _onWriteResult(writeId: string, ok: boolean, error: string | null): void;
}

export interface GameState {
  id: string;
  type: string;
  state: unknown;
  createdAt: number;
  players: Array<{ uid: string; state: unknown; joinedAt: number }>;
}

(function install(): void {
  if (window.TurtleGame) return; // idempotent

  const ANDROID = typeof window.TurtleGame_native !== 'undefined';
  const IOS =
    !ANDROID &&
    !!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.turtleGame);

  type Native = {
    uid(): string | null;
    type(): string;
    artifactId(): string;
    subscribe(): void;
    unsubscribe(): void;
    writePlayerState(id: string, json: string): void;
  } | null;

  const native: Native = (() => {
    if (ANDROID) {
      const n = window.TurtleGame_native!;
      return {
        uid: () => n.uid(),
        type: () => n.type(),
        artifactId: () => n.artifactId(),
        subscribe: () => n.subscribe(),
        unsubscribe: () => n.unsubscribe(),
        writePlayerState: (id, json) => n.writePlayerState(id, json),
      };
    }
    if (IOS) {
      const initial = window.__TurtleGame_initial ?? {};
      const post = (msg: unknown) =>
        window.webkit!.messageHandlers!.turtleGame!.postMessage(msg);
      return {
        uid: () => initial.uid ?? null,
        type: () => initial.type ?? '',
        artifactId: () => initial.artifactId ?? '',
        subscribe: () => post({ method: 'subscribe' }),
        unsubscribe: () => post({ method: 'unsubscribe' }),
        writePlayerState: (id, json) =>
          post({ method: 'writePlayerState', writeId: id, payload: json }),
      };
    }
    return null;
  })();

  const pendingWrites = new Map<string, { resolve(): void; reject(e: Error): void }>();
  let onUpdate: ((s: GameState) => void) | null = null;
  let onError: ((reason: string) => void) | null = null;

  const api: TurtleGameApi = {
    platform: ANDROID ? 'android' : IOS ? 'ios' : 'browser',

    uid: () => (native ? native.uid() : null),
    type: () => (native ? native.type() : ''),
    artifactId: () => (native ? native.artifactId() : ''),

    subscribe(onUpd, onErr) {
      onUpdate = typeof onUpd === 'function' ? onUpd : null;
      onError = typeof onErr === 'function' ? onErr : null;
      if (native) native.subscribe();
    },

    unsubscribe() {
      onUpdate = null;
      onError = null;
      if (native) native.unsubscribe();
    },

    writePlayerState(payload) {
      return new Promise<void>((resolve, reject) => {
        if (!native) {
          reject(new Error('no_bridge'));
          return;
        }
        const writeId = Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        pendingWrites.set(writeId, { resolve, reject });
        try {
          native.writePlayerState(writeId, JSON.stringify(payload));
        } catch (e) {
          pendingWrites.delete(writeId);
          reject(e instanceof Error ? e : new Error(String(e)));
        }
      });
    },

    _onUpdate(jsonString) {
      if (!onUpdate) return;
      try {
        onUpdate(JSON.parse(jsonString) as GameState);
      } catch {
        if (onError) onError('parse_error');
      }
    },

    _onError(reason) {
      if (onError) onError(reason || 'unknown');
    },

    _onWriteResult(writeId, ok, error) {
      const p = pendingWrites.get(writeId);
      if (!p) return;
      pendingWrites.delete(writeId);
      if (ok) p.resolve();
      else p.reject(new Error(error ?? 'unknown'));
    },
  };

  window.TurtleGame = api;
})();
