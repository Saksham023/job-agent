import { motion } from 'framer-motion'
import clsx from 'clsx'
import type { Company } from '../lib/api'
import { Avatar } from './Avatar'

/** The companies as tiles; a click filters the results to that company. */
export function CompanyStrip({
  companies,
  selected,
  onToggle,
}: {
  companies: Company[]
  selected: string[]
  onToggle: (slug: string) => void
}) {
  const shown = companies.filter((c) => c.openJobs > 0)
  return (
    <section className="mx-auto max-w-7xl px-4 sm:px-6">
      <div className="mb-3 flex items-end justify-between">
        <h2 className="font-display text-sm font-semibold tracking-wide text-slate-500 uppercase dark:text-slate-400">
          Hiring right now
        </h2>
        <span className="text-xs text-slate-400">click to filter</span>
      </div>
      <div className="scrollbar-thin -mx-4 flex gap-2.5 overflow-x-auto px-4 pb-3 sm:-mx-6 sm:px-6">
        {shown.map((company, i) => {
          const active = selected.includes(company.slug)
          return (
            <motion.button
              key={company.slug}
              initial={{ opacity: 0, x: 16 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: Math.min(i * 0.025, 0.6) }}
              whileHover={{ y: -3 }}
              onClick={() => onToggle(company.slug)}
              className={clsx(
                'glass flex shrink-0 items-center gap-2.5 rounded-2xl py-2 pr-4 pl-2 text-left transition',
                active && 'border-violet-500/70 ring-2 ring-violet-500/40 dark:border-violet-400/60',
              )}
            >
              <Avatar slug={company.slug} name={company.name} size="sm" />
              <span>
                <span className="block text-sm font-medium whitespace-nowrap text-slate-800 dark:text-slate-100">
                  {company.name}
                </span>
                <span className="block text-[11px] text-slate-500 dark:text-slate-400">
                  {company.openJobs.toLocaleString('en-IN')} roles
                </span>
              </span>
            </motion.button>
          )
        })}
      </div>
    </section>
  )
}
