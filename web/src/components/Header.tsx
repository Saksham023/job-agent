import { Radar } from 'lucide-react'
import { ThemeToggle } from './ThemeToggle'
import { ago } from '../lib/format'

export function Header({ lastCrawlAt, onHome }: { lastCrawlAt: string | null | undefined; onHome: () => void }) {
  return (
    <header className="sticky top-0 z-30 border-b border-slate-200/60 bg-slate-50/70 backdrop-blur-xl dark:border-white/5 dark:bg-ink-950/60">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
        <button onClick={onHome} className="group flex items-center gap-2.5">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-violet-500 to-cyan-500 text-white shadow-lg shadow-violet-500/30 transition group-hover:rotate-12">
            <Radar size={19} />
          </span>
          <span className="font-display text-lg font-semibold tracking-tight text-slate-900 dark:text-white">
            Job<span className="text-gradient">Radar</span>
          </span>
        </button>
        <div className="flex items-center gap-3">
          {lastCrawlAt && (
            <span className="hidden items-center gap-2 rounded-full px-3 py-1.5 text-xs text-slate-500 sm:flex dark:text-slate-400">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
              </span>
              Updated {ago(lastCrawlAt)}
            </span>
          )}
          <ThemeToggle />
        </div>
      </div>
    </header>
  )
}
