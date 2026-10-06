import { motion } from 'framer-motion'
import { ArrowRight, Search, Sparkles } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { Filters, Totals } from '../lib/api'
import { CountUp } from './CountUp'

type Preset = { label: string; emoji: string; filters: Partial<Filters> }

const PRESETS: Preset[] = [
  { label: 'Backend · 0–3 yrs', emoji: '⚙️', filters: { families: ['SOFTWARE_ENGINEERING'], minYears: 0, maxYears: 3 } },
  { label: 'AI & Data', emoji: '🧠', filters: { families: ['DATA_ML'] } },
  { label: 'Remote', emoji: '🌏', filters: { cities: ['Remote'] } },
  { label: 'Posted this week', emoji: '⚡', filters: { postedWithinDays: 7 } },
  { label: 'DevOps & Cloud', emoji: '☁️', filters: { families: ['INFRA_DEVOPS'] } },
  { label: 'Chips & Hardware', emoji: '🔬', filters: { families: ['HARDWARE_ENGINEERING'] } },
]

const rise = { hidden: { opacity: 0, y: 18 }, show: { opacity: 1, y: 0 } }

export function Hero({
  totals,
  query,
  onSearch,
  onPreset,
}: {
  totals: Totals | undefined
  query: string
  onSearch: (q: string) => void
  onPreset: (filters: Partial<Filters>) => void
}) {
  const [text, setText] = useState(query)
  useEffect(() => setText(query), [query])

  return (
    <section className="relative overflow-hidden">
      <div className="grid-bg pointer-events-none absolute inset-0" />
      <motion.div
        className="relative mx-auto max-w-4xl px-4 pt-16 pb-12 text-center sm:px-6 sm:pt-24"
        initial="hidden"
        animate="show"
        transition={{ staggerChildren: 0.09 }}
      >
        <motion.div
          variants={rise}
          className="glass mx-auto mb-6 inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-xs font-medium text-slate-600 dark:text-slate-300"
        >
          <Sparkles size={14} className="text-violet-500" />
          Straight from {totals ? totals.companies : '30+'} companies&apos; own careers sites · no sign-up
        </motion.div>

        <motion.h1
          variants={rise}
          className="font-display text-4xl leading-[1.05] font-bold tracking-tight text-slate-900 sm:text-6xl dark:text-white"
        >
          Every tech job in India,
          <br />
          <span className="text-gradient">from the source.</span>
        </motion.h1>

        <motion.p variants={rise} className="mx-auto mt-5 max-w-2xl text-base text-slate-600 sm:text-lg dark:text-slate-400">
          Live openings crawled from Amazon, JPMorgan, Qualcomm, Nvidia and more, sorted into job families with the
          experience each role really asks for. Pick what fits you and apply on the company&apos;s own page.
        </motion.p>

        <motion.form
          variants={rise}
          onSubmit={(e) => {
            e.preventDefault()
            onSearch(text.trim())
          }}
          className="glass group mx-auto mt-9 flex max-w-2xl items-center gap-2 rounded-2xl p-2 shadow-2xl shadow-violet-500/10 transition focus-within:ring-2 focus-within:ring-violet-500/50"
        >
          <Search className="ml-3 shrink-0 text-slate-400" size={20} />
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Search a title or company: “backend”, “Visa”, “data engineer”…"
            className="min-w-0 flex-1 bg-transparent px-2 py-3 text-base text-slate-900 outline-none placeholder:text-slate-400 dark:text-white"
          />
          <button
            type="submit"
            className="flex shrink-0 items-center gap-1.5 rounded-xl bg-gradient-to-r from-violet-600 to-cyan-500 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-violet-600/30 transition hover:brightness-110 active:scale-95"
          >
            Search <ArrowRight size={16} />
          </button>
        </motion.form>

        <motion.div variants={rise} className="mt-5 flex flex-wrap justify-center gap-2">
          {PRESETS.map((preset) => (
            <motion.button
              key={preset.label}
              whileHover={{ y: -2 }}
              whileTap={{ scale: 0.95 }}
              onClick={() => onPreset(preset.filters)}
              className="glass rounded-full px-3.5 py-1.5 text-sm text-slate-700 transition hover:border-violet-400/60 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white"
            >
              <span className="mr-1.5">{preset.emoji}</span>
              {preset.label}
            </motion.button>
          ))}
        </motion.div>

        <motion.dl variants={rise} className="mx-auto mt-12 grid max-w-3xl grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            { label: 'Open roles', value: totals?.openJobs },
            { label: 'Companies', value: totals?.companies },
            { label: 'Posted this week', value: totals?.postedThisWeek },
            { label: 'Remote roles', value: totals?.remoteJobs },
          ].map((stat) => (
            <div key={stat.label} className="glass rounded-2xl px-4 py-4">
              <dd className="font-display text-2xl font-bold text-slate-900 sm:text-3xl dark:text-white">
                {stat.value != null ? <CountUp value={stat.value} /> : '—'}
              </dd>
              <dt className="mt-1 text-xs tracking-wide text-slate-500 uppercase dark:text-slate-400">{stat.label}</dt>
            </div>
          ))}
        </motion.dl>
      </motion.div>
    </section>
  )
}
