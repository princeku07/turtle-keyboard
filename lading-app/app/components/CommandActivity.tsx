"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { COMMANDS, type CommandId } from "../commands";

type ActivityState = {
  active: CommandId | null;
  setActive: (cmd: CommandId | null) => void;
  injectCommand: (cmd: CommandId) => void;
  injectListener: ((cmd: CommandId) => void) | null;
  registerInjectListener: (fn: ((cmd: CommandId) => void) | null) => void;
};

const Ctx = createContext<ActivityState | null>(null);

export function CommandActivityProvider({ children }: { children: ReactNode }) {
  const [active, setActive] = useState<CommandId | null>(null);
  const [injectListener, setInjectListener] = useState<
    ((cmd: CommandId) => void) | null
  >(null);

  const injectCommand = useCallback(
    (cmd: CommandId) => {
      injectListener?.(cmd);
    },
    [injectListener],
  );

  const registerInjectListener = useCallback(
    (fn: ((cmd: CommandId) => void) | null) => {
      setInjectListener(() => fn);
    },
    [],
  );

  const value = useMemo(
    () => ({ active, setActive, injectCommand, injectListener, registerInjectListener }),
    [active, injectCommand, injectListener, registerInjectListener],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useCommandActivity() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useCommandActivity must be used inside provider");
  return v;
}

export function useActiveContent() {
  const { active } = useCommandActivity();
  return active ? COMMANDS[active] : null;
}
