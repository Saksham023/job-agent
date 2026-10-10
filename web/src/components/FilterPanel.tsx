import { AnimatePresence, motion } from 'framer-motion'
import clsx from 'clsx'
import { Briefcase, Building2, Calendar, Check, ChevronDown, Layers, MapPin, RotateCcw, Search, Sparkles, X } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Facets, Filters, Meta, UserProfile } from '../lib/api'
import { activeCount, toggle } from '../lib/filters'
import { Avatar } from './Avatar'

type Props = {
  meta: Meta | undefined
  facets: Facets | undefined
  filters: Filters
  update: (change: Partial<Filters>) => void
  reset: () => void
  profile?: UserProfile | null
  onMatch?: (profile: UserProfile) => void
  onAddResume?: () => void
}

export function FilterPanel({ meta, facets, filters, update, reset, profile, onMatch, onAddResume }: Props) {
  const active = activeCount(filters)
  const canMatch = !!profile && (profile.families.length > 0 || profile.jobYearsFrom != null)
  return (
    <div className="space-y-3">
      {onMatch && (
        <button
          onClick={() => (canMatch ? onMatch(profile!) : onAddResume?.())}
          className="group flex w-full items-center gap-3 rounded-2xl border border-violet-500/30 bg-gradient-to-r from-violet-500/10 to-cyan-500/10 px-4 py-3 text-left transition hover:border-violet-500/60"
        >
          <span className="grid h-8 w-8 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-violet-500 to-cyan-500 text-white">
            <Sparkles size={15} />
          </span>
          <span className="min-w-0">
            <span className="block text-sm font-semibold text-slate-900 dark:text-white">Match my resume</span>
            <span className="block truncate text-xs text-slate-500 dark:text-slate-400">
              {canMatch
                ? `${profile!.families.length} famil${profile!.families.length === 1 ? 'y' : 'ies'}${profile!.jobYearsFrom != null ? ` · ${profile!.jobYearsFrom}–${profile!.jobYearsTo} yrs` : ''}`
                : 'Add your resume to use this'}
            </span>
          </span>
        </button>
      )}
      <div className="flex items-center justify-between px-1">
        <h2 className="font-display text-base font-semibold text-slate-900 dark:text-white">Filters</h2>
        <AnimatePresence>
          {active > 0 && (
            <motion.button
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              onClick={reset}
              className="flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium text-violet-600 hover:bg-violet-500/10 dark:text-violet-300"
            >
              <RotateCcw size={12} /> Reset all ({active})
            </motion.button>
          )}
        </AnimatePresence>
      </div>

      <Section icon={<Layers size={15} />} title="Job family" badge={filters.families.length}>
        <FamilyPicker meta={meta} facets={facets} selected={filters.families} onChange={(families) => update({ families })} />
      </Section>

      <Section icon={<Briefcase size={15} />} title="Experience" badge={filters.minYears != null || filters.maxYears != null ? 1 : 0}>
        <ExperiencePicker filters={filters} update={update} />
      </Section>

      <Section icon={<Building2 size={15} />} title="Companies" badge={filters.companies.length}>
        <CompanyPicker meta={meta} facets={facets} selected={filters.companies} onChange={(companies) => update({ companies })} />
      </Section>

      <Section icon={<MapPin size={15} />} title="Location" badge={filters.cities.length}>
        <LocationPicker meta={meta} facets={facets} selected={filters.cities} onChange={(cities) => update({ cities })} />
      </Section>

      <Section icon={<Sparkles size={15} />} title="Skills" badge={filters.skills.length}>
        <SkillPicker meta={meta} facets={facets} selected={filters.skills} onChange={(skills) => update({ skills })} />
      </Section>

      <Section icon={<Calendar size={15} />} title="Posted" badge={filters.postedWithinDays != null ? 1 : 0}>
        <Segmented
          value={filters.postedWithinDays}
          options={[
            { value: null, label: 'Any time' },
            { value: 1, label: '24h' },
            { value: 7, label: '7 days' },
            { value: 30, label: '30 days' },
          ]}
          onChange={(postedWithinDays) => update({ postedWithinDays })}
        />
      </Section>
    </div>
  )
}

// ------------------------------------------------------------------ section shell

function Section({ icon, title, badge, children }: { icon: ReactNode; title: string; badge: number; children: ReactNode }) {
  const [open, setOpen] = useState(true)
  return (
    <div className="glass overflow-hidden rounded-2xl">
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2.5 px-4 py-3 text-left text-sm font-semibold text-slate-800 dark:text-slate-100"
      >
        <span className="text-violet-500 dark:text-violet-400">{icon}</span>
        <span className="flex-1">{title}</span>
        {badge > 0 && (
          <span className="rounded-full bg-violet-500 px-1.5 text-[11px] leading-5 font-semibold text-white">{badge}</span>
        )}
        <ChevronDown size={16} className={clsx('text-slate-400 transition-transform', open && 'rotate-180')} />
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.22, ease: 'easeOut' }}
          >
            <div className="px-4 pb-4">{children}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function Chip({ active, onClick, children, count }: { active: boolean; onClick: () => void; children: ReactNode; count?: number }) {
  return (
    <motion.button
      layout
      whileTap={{ scale: 0.94 }}
      onClick={onClick}
      className={clsx(
        'inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[13px] transition',
        !active && count === 0 && 'opacity-40',
        active
          ? 'border-transparent bg-gradient-to-r from-violet-600 to-indigo-500 text-white shadow-md shadow-violet-600/25'
          : 'border-slate-200 bg-white/60 text-slate-700 hover:border-violet-400/60 hover:text-slate-900 dark:border-white/10 dark:bg-white/[0.03] dark:text-slate-300 dark:hover:text-white',
      )}
    >
      {active && <Check size={13} />}
      {children}
      {count != null && <span className={clsx('text-[11px]', active ? 'text-white/75' : 'text-slate-400')}>{count.toLocaleString('en-IN')}</span>}
    </motion.button>
  )
}

// ------------------------------------------------------------------ families

const GROUPS = [
  { id: 'TECH', label: 'Engineering' },
  { id: 'TECH_ADJACENT', label: 'Tech-adjacent' },
  { id: 'BUSINESS', label: 'Business' },
] as const

function FamilyPicker({ meta, facets, selected, onChange }: { meta: Meta | undefined; facets: Facets | undefined; selected: string[]; onChange: (v: string[]) => void }) {
  const [showBusiness, setShowBusiness] = useState(false)
  if (!meta) return <Loading />
  return (
    <div className="space-y-3">
      {GROUPS.filter((g) => g.id !== 'BUSINESS' || showBusiness || meta.families.some((f) => f.group === 'BUSINESS' && selected.includes(f.id))).map((group) => (
        <div key={group.id}>
          <p className="mb-1.5 text-[11px] font-medium tracking-wide text-slate-400 uppercase">{group.label}</p>
          <div className="flex flex-wrap gap-1.5">
            {meta.families
              .filter((f) => f.group === group.id)
              .map((family) => (
                <Chip key={family.id} active={selected.includes(family.id)} count={facets ? (facets.families[family.id] ?? 0) : family.jobs} onClick={() => onChange(toggle(selected, family.id))}>
                  {family.label}
                </Chip>
              ))}
          </div>
        </div>
      ))}
      {!showBusiness && (
        <button onClick={() => setShowBusiness(true)} className="text-xs font-medium text-violet-600 hover:underline dark:text-violet-300">
          + Business roles (sales, finance, operations…)
        </button>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ experience

const RANGES: { label: string; min: number | null; max: number | null }[] = [
  { label: 'Fresher', min: 0, max: 1 },
  { label: '0–2', min: 0, max: 2 },
  { label: '2–5', min: 2, max: 5 },
  { label: '5–8', min: 5, max: 8 },
  { label: '8+', min: 8, max: null },
]

function ExperiencePicker({ filters, update }: { filters: Filters; update: (change: Partial<Filters>) => void }) {
  const [from, setFrom] = useState(filters.minYears?.toString() ?? '')
  const [to, setTo] = useState(filters.maxYears?.toString() ?? '')
  useEffect(() => setFrom(filters.minYears?.toString() ?? ''), [filters.minYears])
  useEffect(() => setTo(filters.maxYears?.toString() ?? ''), [filters.maxYears])

  const parse = (v: string) => (v.trim() === '' ? null : Math.max(0, Math.min(50, Number.parseInt(v, 10) || 0)))
  const error =
    parse(from) != null && parse(to) != null && (parse(from) as number) > (parse(to) as number) ? '“From” is more than “to”' : null
  const commit = () => {
    if (!error) update({ minYears: parse(from), maxYears: parse(to) })
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1.5">
        {RANGES.map((r) => (
          <Chip
            key={r.label}
            active={filters.minYears === r.min && filters.maxYears === r.max}
            onClick={() =>
              filters.minYears === r.min && filters.maxYears === r.max
                ? update({ minYears: null, maxYears: null })
                : update({ minYears: r.min, maxYears: r.max })
            }
          >
            {r.label}
          </Chip>
        ))}
      </div>
      <div className="flex items-center gap-2">
        <YearInput label="From" value={from} onChange={setFrom} onCommit={commit} />
        <span className="pt-4 text-slate-400">–</span>
        <YearInput label="To" value={to} onChange={setTo} onCommit={commit} />
        <span className="pt-4 text-xs text-slate-400">years</span>
      </div>
      {error && <p className="text-xs text-rose-500">{error}</p>}
      <label className="flex cursor-pointer items-start gap-2.5 text-[13px] text-slate-600 select-none dark:text-slate-400">
        <Toggle on={filters.includeUnstated} onChange={() => update({ includeUnstated: !filters.includeUnstated })} />
        <span>Also show roles that don&apos;t state the experience they need</span>
      </label>
    </div>
  )
}

function YearInput({ label, value, onChange, onCommit }: { label: string; value: string; onChange: (v: string) => void; onCommit: () => void }) {
  return (
    <label className="flex-1">
      <span className="mb-1 block text-[11px] font-medium tracking-wide text-slate-400 uppercase">{label}</span>
      <input
        type="number"
        min={0}
        max={50}
        inputMode="numeric"
        value={value}
        placeholder="any"
        onChange={(e) => onChange(e.target.value)}
        onBlur={onCommit}
        onKeyDown={(e) => e.key === 'Enter' && onCommit()}
        className="w-full rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-sm text-slate-900 outline-none focus:border-violet-500 focus:ring-2 focus:ring-violet-500/30 dark:border-white/10 dark:bg-white/[0.04] dark:text-white"
      />
    </label>
  )
}

function Toggle({ on, onChange }: { on: boolean; onChange: () => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      onClick={onChange}
      className={clsx('relative mt-0.5 h-5 w-9 shrink-0 rounded-full transition', on ? 'bg-violet-500' : 'bg-slate-300 dark:bg-white/15')}
    >
      <motion.span layout className={clsx('absolute top-0.5 h-4 w-4 rounded-full bg-white shadow', on ? 'right-0.5' : 'left-0.5')} />
    </button>
  )
}

// ------------------------------------------------------------------ companies

function CompanyPicker({ meta, facets, selected, onChange }: { meta: Meta | undefined; facets: Facets | undefined; selected: string[]; onChange: (v: string[]) => void }) {
  const [search, setSearch] = useState('')
  const [all, setAll] = useState(false)
  if (!meta) return <Loading />
  const live = (slug: string, fallback: number) => (facets ? (facets.companies[slug] ?? 0) : fallback)
  // the selected companies first, then by how many roles they have under the current filters
  const matching = meta.companies
    .filter((c) => c.openJobs > 0 && c.name.toLowerCase().includes(search.toLowerCase()))
    .map((c) => ({ ...c, live: live(c.slug, c.openJobs) }))
    .sort((a, b) => Number(selected.includes(b.slug)) - Number(selected.includes(a.slug)) || b.live - a.live)
  const shown = all || search ? matching : matching.slice(0, 8)
  return (
    <div className="space-y-2">
      <SearchBox value={search} onChange={setSearch} placeholder="Find a company" />
      <ul className="scrollbar-thin max-h-72 space-y-0.5 overflow-y-auto pr-1">
        {shown.map((company) => {
          const active = selected.includes(company.slug)
          return (
            <li key={company.slug}>
              <button
                onClick={() => onChange(toggle(selected, company.slug))}
                className={clsx(
                  'flex w-full items-center gap-2.5 rounded-xl px-2 py-1.5 text-left text-sm transition',
                  active ? 'bg-violet-500/10 text-slate-900 dark:text-white' : 'text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5',
                  !active && company.live === 0 && 'opacity-40',
                )}
              >
                <Avatar slug={company.slug} name={company.name} size="sm" />
                <span className="flex-1 truncate">{company.name}</span>
                <span className="text-xs text-slate-400 tabular-nums">{company.live.toLocaleString('en-IN')}</span>
                <span className={clsx('grid h-4 w-4 place-items-center rounded border', active ? 'border-violet-500 bg-violet-500 text-white' : 'border-slate-300 dark:border-white/20')}>
                  {active && <Check size={11} strokeWidth={3} />}
                </span>
              </button>
            </li>
          )
        })}
        {shown.length === 0 && <li className="px-2 py-2 text-xs text-slate-400">No company matches “{search}”.</li>}
      </ul>
      {!search && matching.length > 8 && (
        <button onClick={() => setAll(!all)} className="px-2 text-xs font-medium text-violet-600 hover:underline dark:text-violet-300">
          {all ? 'Show fewer' : `Show all ${matching.length} companies`}
        </button>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ locations

function LocationPicker({ meta, facets, selected, onChange }: { meta: Meta | undefined; facets: Facets | undefined; selected: string[]; onChange: (v: string[]) => void }) {
  const [more, setMore] = useState(false)
  if (!meta) return <Loading />
  const cities = more ? meta.cities : meta.cities.slice(0, 9)
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5">
        <Chip active={selected.includes('Remote')} count={facets ? facets.remote : meta.totals.remoteJobs} onClick={() => onChange(toggle(selected, 'Remote'))}>
          🌏 Remote
        </Chip>
        {cities.map((city) => (
          <Chip key={city.name} active={selected.includes(city.name)} count={facets ? (facets.cities[city.name] ?? 0) : city.jobs} onClick={() => onChange(toggle(selected, city.name))}>
            {city.name}
          </Chip>
        ))}
      </div>
      {meta.cities.length > 9 && (
        <button onClick={() => setMore(!more)} className="text-xs font-medium text-violet-600 hover:underline dark:text-violet-300">
          {more ? 'Fewer cities' : `More cities (${meta.cities.length - 9})`}
        </button>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ skills

function SkillPicker({ meta, facets, selected, onChange }: { meta: Meta | undefined; facets: Facets | undefined; selected: string[]; onChange: (v: string[]) => void }) {
  const [search, setSearch] = useState('')
  const suggestions = useMemo(() => {
    if (!meta) return []
    const q = search.toLowerCase()
    // the skills the current results mention most, each with the roles left after adding it
    const pool = facets ? facets.skills : meta.skills
    return pool.filter((s) => !selected.includes(s.name) && s.name.toLowerCase().includes(q)).slice(0, q ? 12 : 14)
  }, [meta, facets, search, selected])
  if (!meta) return <Loading />
  return (
    <div className="space-y-2.5">
      <AnimatePresence initial={false}>
        {selected.length > 0 && (
          <motion.div initial={{ height: 0 }} animate={{ height: 'auto' }} exit={{ height: 0 }} className="flex flex-wrap gap-1.5 overflow-hidden">
            {selected.map((skill) => (
              <motion.button
                layout
                key={skill}
                initial={{ scale: 0.8, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                onClick={() => onChange(selected.filter((s) => s !== skill))}
                className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-violet-600 to-indigo-500 px-3 py-1.5 text-[13px] text-white"
              >
                {skill} <X size={13} />
              </motion.button>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
      <SearchBox value={search} onChange={setSearch} placeholder="Find a skill: Kafka, React, PyTorch…" />
      <div className="flex flex-wrap gap-1.5">
        {suggestions.map((skill) => (
          <Chip key={skill.name} active={false} count={skill.jobs} onClick={() => { onChange([...selected, skill.name]); setSearch('') }}>
            {skill.name}
          </Chip>
        ))}
        {suggestions.length === 0 && <span className="text-xs text-slate-400">No skill matches “{search}”.</span>}
      </div>
      {selected.length > 1 && <p className="text-[11px] text-slate-400">Showing roles that mention all {selected.length} skills.</p>}
    </div>
  )
}

// ------------------------------------------------------------------ small pieces

function Segmented<T>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="relative grid grid-flow-col rounded-xl bg-slate-100 p-1 dark:bg-white/5">
      {options.map((option) => {
        const active = option.value === value
        return (
          <button key={option.label} onClick={() => onChange(option.value)} className="relative z-10 rounded-lg px-2 py-1.5 text-[13px] font-medium">
            {active && (
              <motion.span layoutId="segmented" className="absolute inset-0 -z-10 rounded-lg bg-white shadow-sm dark:bg-white/15" transition={{ type: 'spring', bounce: 0.2, duration: 0.4 }} />
            )}
            <span className={active ? 'text-slate-900 dark:text-white' : 'text-slate-500 dark:text-slate-400'}>{option.label}</span>
          </button>
        )
      })}
    </div>
  )
}

function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white/70 px-3 focus-within:border-violet-500 focus-within:ring-2 focus-within:ring-violet-500/30 dark:border-white/10 dark:bg-white/[0.04]">
      <Search size={14} className="shrink-0 text-slate-400" />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full bg-transparent py-2 text-sm text-slate-900 outline-none placeholder:text-slate-400 dark:text-white"
      />
      {value && (
        <button onClick={() => onChange('')} className="text-slate-400 hover:text-slate-600" aria-label="Clear">
          <X size={14} />
        </button>
      )}
    </div>
  )
}

function Loading() {
  return <div className="h-16 animate-pulse rounded-xl bg-slate-200/60 dark:bg-white/5" />
}
