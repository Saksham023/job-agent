import { motion } from 'framer-motion'
import clsx from 'clsx'
import { ArrowUpRight, Briefcase, Globe2, MapPin } from 'lucide-react'
import type { JobCard as Job } from '../lib/api'
import { ago, FAMILY_SHORT, isFresh, years } from '../lib/format'
import { Avatar } from './Avatar'

export function JobCard({ job, index, onOpen }: { job: Job; index: number; onOpen: () => void }) {
  const fresh = isFresh(job.postedAt)
  const place = job.cities.length > 0 ? job.cities.slice(0, 2).join(', ') + (job.cities.length > 2 ? ` +${job.cities.length - 2}` : '') : null
  return (
    <motion.article
      layout
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.97 }}
      transition={{ duration: 0.3, delay: Math.min((index % 24) * 0.025, 0.4) }}
      whileHover={{ y: -3 }}
      onClick={onOpen}
      className="glass group relative cursor-pointer overflow-hidden rounded-2xl p-5 transition-shadow hover:shadow-xl hover:shadow-violet-500/10"
    >
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-violet-500/0 via-transparent to-cyan-500/0 opacity-0 transition-opacity duration-300 group-hover:from-violet-500/[0.07] group-hover:to-cyan-500/[0.06] group-hover:opacity-100" />
      <div className="relative flex items-start gap-3.5">
        <Avatar slug={job.companySlug} name={job.company} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 text-[13px] text-slate-500 dark:text-slate-400">
            <span className="truncate font-medium text-slate-600 dark:text-slate-300">{job.company}</span>
            <span className="text-slate-300 dark:text-slate-600">•</span>
            <span className="shrink-0">{ago(job.postedAt)}</span>
            {fresh && (
              <span className="shrink-0 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-semibold tracking-wide text-emerald-600 uppercase dark:text-emerald-400">
                New
              </span>
            )}
          </div>
          <h3 className="mt-1 line-clamp-2 font-display text-[17px] leading-snug font-semibold text-slate-900 transition-colors group-hover:text-violet-700 dark:text-white dark:group-hover:text-violet-300">
            {job.title}
          </h3>
          <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[13px] text-slate-600 dark:text-slate-400">
            {place && (
              <span className="flex items-center gap-1.5">
                <MapPin size={14} className="text-slate-400" /> {place}
              </span>
            )}
            {job.remote && (
              <span className="flex items-center gap-1.5 text-cyan-600 dark:text-cyan-400">
                <Globe2 size={14} /> Remote
              </span>
            )}
            <span className={clsx('flex items-center gap-1.5', !job.yearsStated && 'text-slate-400 italic')}>
              <Briefcase size={14} className="text-slate-400" /> {years(job.minYears, job.maxYears, job.yearsStated)}
            </span>
          </div>
        </div>
      </div>

      <div className="relative mt-4 flex items-end justify-between gap-3">
        <div className="flex min-w-0 flex-wrap gap-1.5">
          {job.family && (
            <span className="rounded-md bg-violet-500/10 px-2 py-1 text-[11px] font-semibold text-violet-700 dark:text-violet-300">
              {FAMILY_SHORT[job.family] ?? job.family}
            </span>
          )}
          {job.skills.slice(0, 4).map((skill) => (
            <span key={skill} className="rounded-md bg-slate-100 px-2 py-1 text-[11px] font-medium text-slate-600 dark:bg-white/[0.06] dark:text-slate-300">
              {skill}
            </span>
          ))}
        </div>
        <a
          href={job.url}
          target="_blank"
          rel="noreferrer"
          onClick={(e) => e.stopPropagation()}
          className="flex shrink-0 items-center gap-1 rounded-full bg-slate-900 px-3.5 py-1.5 text-xs font-semibold text-white transition hover:bg-violet-600 dark:bg-white dark:text-slate-900 dark:hover:bg-violet-300"
        >
          Apply <ArrowUpRight size={13} />
        </a>
      </div>
    </motion.article>
  )
}
