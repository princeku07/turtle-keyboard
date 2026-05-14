import { COMMANDS, type CommandId } from "../commands";

export default function CommandTile({ cmd }: { cmd: CommandId }) {
  const c = COMMANDS[cmd];
  return (
    <a
      href="#waitlist"
      className="group relative rounded-2xl bg-white/5 hover:bg-white/[0.08] backdrop-blur-md border border-white/10 hover:border-cyan/40 p-6 sm:p-7 min-h-[160px] flex flex-col justify-between overflow-hidden transition-all hover:-translate-y-0.5"
    >
      <div className="flex items-start justify-between">
        <span className="font-mono text-2xl md:text-3xl tracking-tight text-foam">
          {c.cmd}
        </span>
        <span className="font-mono text-[10px] uppercase tracking-widest text-foam/35 group-hover:text-cyan transition-colors">
          waitlist ↗
        </span>
      </div>
      <p className="font-mono text-sm leading-snug text-foam/65 group-hover:text-foam/85 transition-colors">
        {c.hint}.
      </p>
    </a>
  );
}
