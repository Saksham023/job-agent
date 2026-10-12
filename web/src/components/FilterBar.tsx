import { AnimatePresence, motion } from 'framer-motion'
import clsx from 'clsx'
import { Building2, ChevronDown, ListChecks, MapPin, SlidersHorizontal, Sparkles } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { Facets, Filters, Meta, UserProfile } from '../lib/api'
import { activeCount } from '../lib/filters'
import { CompanyPicker, LocationPicker, MatchResumeButton, myJobsCount, MyJobsPicker, SkillPicker } from './FilterPanel'

type Props = {
  meta: Meta | undefined
  facets: Facets | undefined
  filters: Filters
  update: (change: Partial<Filters>) => void
  profile?: UserProfile | null
  onMatch: (profile: UserProfile) => void
  onAddResume: () => void
  onAllFilters: () => void
}

/**
 * The quick filters above the results: the ones changed on most visits (resume match, companies, locations, skills,
 * my jobs), each opening a small panel with the same picker as the full list; "All filters" opens everything.
 * The row scrolls sideways on a phone.
 */
export function FilterBar({ meta, facets, filters, update, profile, onMatch, onAddResume, onAllFilters }: Props) {
  const companyName = (slug: string) => meta?.companies.find((c) => c.slug === slug)?.name ?? slug
  return (
    <div className="sticky top-16 z-20 -mx-4 border-b border-slate-200/60 bg-slate-50/85 px-4 py-2.5 backdrop-blur-xl sm:-mx-6 sm:px-6 dark:border-white/5 dark:bg-ink-950/85">
      <div className="scrollbar-none flex items-center gap-2 overflow-x-auto">
        <MatchResumeButton compact profile={profile} onMatch={onMatch} onAddResume={onAddResume} />
        <Dropdown icon={<Building2 size={14} />} label="Companies" selected={filters.companies.map(companyName)}>
          <CompanyPicker meta={meta} facets={facets} selected={filters.companies} onChange={(companies) => update({ companies })} />
        </Dropdown>
        <Dropdown icon={<MapPin size={14} />} label="Location" selected={filters.cities}>
          <LocationPicker meta={meta} facets={facets} selected={filters.cities} onChange={(cities) => update({ cities })} />
        </Dropdown>
        <Dropdown icon={<Sparkles size={14} />} label="Skills" selected={filters.skills}>
          <SkillPicker meta={meta} facets={facets} selected={filters.skills} onChange={(skills) => update({ skills })} />
        </Dropdown>
        <Dropdown icon={<ListChecks size={14} />} label="My jobs" count={myJobsCount(filters)}>
          <MyJobsPicker facets={facets} filters={filters} update={update} />
        </Dropdown>
        <button
          onClick={onAllFilters}
          className={clsx(
            'flex shrink-0 items-center gap-1.5 rounded-full border px-3.5 py-2 text-[13px] font-medium transition',
            'border-slate-200 bg-white/70 text-slate-700 hover:border-violet-400 hover:text-violet-700 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200 dark:hover:text-violet-300',
          )}
        >
          <SlidersHorizontal size={14} /> All filters
          {activeCount(filters) > 0 && <Badge n={activeCount(filters)} />}
        </button>
      </div>
    </div>
  )
}

function Badge({ n }: { n: number }) {
  return <span className="rounded-full bg-violet-500 px-1.5 text-[11px] leading-5 font-semibold text-white">{n}</span>
}

/**
 * A pill that opens a panel under it. The panel is drawn in a layer over the page (the bar scrolls sideways and would
 * clip it), follows the pill when the page scrolls, and closes on Esc, a click outside, or when the pill leaves the screen.
 */
function Dropdown({ icon, label, selected, count, children }: { icon: ReactNode; label: string; selected?: string[]; count?: number; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const [place, setPlace] = useState<{ top: number; left: number; width: number; maxHeight: number } | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const n = count ?? selected?.length ?? 0
  const summary = selected && selected.length > 0 ? selected[0] + (selected.length > 1 ? ` +${selected.length - 1}` : '') : null

  const position = () => {
    if (!button.current) return
    const r = button.current.getBoundingClientRect()
    if (r.bottom < 0 || r.top > window.innerHeight) {
      setOpen(false)
      return
    }
    const width = Math.min(380, window.innerWidth - 16)
    setPlace({
      top: r.bottom + 8,
      left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
      width,
      maxHeight: Math.max(220, window.innerHeight - r.bottom - 24),
    })
  }

  useLayoutEffect(() => {
    if (open) position()
  }, [open])

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !panel.current?.contains(e.target as Node) && !button.current?.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    window.addEventListener('scroll', position, true)
    window.addEventListener('resize', position)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
      window.removeEventListener('scroll', position, true)
      window.removeEventListener('resize', position)
    }
  }, [open])

  return (
    <>
      <button
        ref={button}
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-haspopup="dialog"
        className={clsx(
          'flex shrink-0 items-center gap-1.5 rounded-full border px-3.5 py-2 text-[13px] font-medium transition',
          n > 0 || open
            ? 'border-violet-500/50 bg-violet-500/10 text-violet-700 dark:text-violet-200'
            : 'border-slate-200 bg-white/70 text-slate-700 hover:border-violet-400 hover:text-violet-700 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200 dark:hover:text-violet-300',
        )}
      >
        {icon}
        <span className="max-w-[10rem] truncate">{summary ?? label}</span>
        {count != null && count > 0 && <Badge n={count} />}
        <ChevronDown size={14} className={clsx('opacity-60 transition-transform', open && 'rotate-180')} />
      </button>
      {createPortal(
        <AnimatePresence>
          {open && place && (
            <motion.div
              ref={panel}
              role="dialog"
              aria-label={label}
              initial={{ opacity: 0, y: -4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.14 }}
              style={{ top: place.top, left: place.left, width: place.width, maxHeight: place.maxHeight }}
              className="scrollbar-thin fixed z-[60] overflow-y-auto rounded-2xl border border-slate-200/80 bg-white p-4 shadow-xl shadow-slate-900/10 dark:border-white/10 dark:bg-ink-900 dark:shadow-2xl dark:shadow-black/60"
            >
              <p className="mb-3 text-sm font-semibold text-slate-900 dark:text-white">{label}</p>
              {children}
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </>
  )
}
