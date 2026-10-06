import { AnimatePresence, motion } from 'framer-motion'
import { ArrowDownUp, X } from 'lucide-react'
import type { Filters, Meta, Sort } from '../lib/api'
import { CountUp } from './CountUp'

type Pill = { key: string; label: string; remove: () => void }

/** "1,234 roles", the active filters as removable pills, and the sort. */
export function ResultsBar({
  total,
  loading,
  filters,
  meta,
  update,
}: {
  total: number | undefined
  loading: boolean
  filters: Filters
  meta: Meta | undefined
  update: (change: Partial<Filters>) => void
}) {
  const companyName = (slug: string) => meta?.companies.find((c) => c.slug === slug)?.name ?? slug
  const familyName = (id: string) => meta?.families.find((f) => f.id === id)?.label ?? id
  const range =
    filters.minYears != null && filters.maxYears != null
      ? `${filters.minYears}–${filters.maxYears} yrs`
      : filters.minYears != null
        ? `${filters.minYears}+ yrs`
        : filters.maxYears != null
          ? `up to ${filters.maxYears} yrs`
          : null

  const pills: Pill[] = [
    ...(filters.q ? [{ key: 'q', label: `“${filters.q}”`, remove: () => update({ q: '' }) }] : []),
    ...filters.families.map((f) => ({ key: `f-${f}`, label: familyName(f), remove: () => update({ families: filters.families.filter((x) => x !== f) }) })),
    ...(range ? [{ key: 'years', label: range, remove: () => update({ minYears: null, maxYears: null }) }] : []),
    ...filters.companies.map((c) => ({ key: `c-${c}`, label: companyName(c), remove: () => update({ companies: filters.companies.filter((x) => x !== c) }) })),
    ...filters.cities.map((c) => ({ key: `l-${c}`, label: c, remove: () => update({ cities: filters.cities.filter((x) => x !== c) }) })),
    ...filters.skills.map((s) => ({ key: `s-${s}`, label: s, remove: () => update({ skills: filters.skills.filter((x) => x !== s) }) })),
    ...(filters.postedWithinDays != null
      ? [{ key: 'posted', label: filters.postedWithinDays === 1 ? 'Last 24h' : `Last ${filters.postedWithinDays} days`, remove: () => update({ postedWithinDays: null }) }]
      : []),
  ]

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-2xl font-bold text-slate-900 dark:text-white">
          {total == null ? (
            <span className="text-slate-400">Searching…</span>
          ) : (
            <>
              <span className={loading ? 'opacity-50 transition' : 'transition'}>
                <CountUp key={total} value={total} duration={0.6} />
              </span>{' '}
              <span className="text-lg font-medium text-slate-500 dark:text-slate-400">{total === 1 ? 'role' : 'roles'}</span>
            </>
          )}
        </h2>
        <label className="glass flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-slate-600 dark:text-slate-300">
          <ArrowDownUp size={15} className="text-slate-400" />
          <select
            value={filters.sort}
            onChange={(e) => update({ sort: e.target.value as Sort })}
            className="cursor-pointer bg-transparent font-medium text-slate-800 outline-none dark:text-slate-100"
          >
            <option value="newest">Newest first</option>
            <option value="experience">Least experience first</option>
            <option value="company">Company A–Z</option>
          </select>
        </label>
      </div>
      <div className="flex flex-wrap gap-1.5">
        <AnimatePresence initial={false}>
          {pills.map((pill) => (
            <motion.button
              layout
              key={pill.key}
              initial={{ opacity: 0, scale: 0.85 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.85 }}
              onClick={pill.remove}
              className="group inline-flex items-center gap-1.5 rounded-full border border-violet-500/30 bg-violet-500/10 px-3 py-1 text-[13px] font-medium text-violet-700 hover:bg-violet-500/20 dark:text-violet-200"
            >
              {pill.label}
              <X size={13} className="opacity-60 group-hover:opacity-100" />
            </motion.button>
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}
