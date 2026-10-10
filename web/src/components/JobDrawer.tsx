import { useQuery } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { ArrowUpRight, Briefcase, Building2, Calendar, Check, Copy, Globe2, MapPin, MessageSquareText, Quote, Users, X } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { fetchJob, fetchProfile, fetchReferralTemplate, type JobDetail } from '../lib/api'
import { connectionsUrl } from '../lib/linkedin'
import { fillReferral } from '../lib/referral'
import { ago, FAMILY_SHORT, years } from '../lib/format'
import { Avatar } from './Avatar'

/** The job opened from a card: slides in from the right, closes with Esc, the backdrop or the X. */
export function JobDrawer({ jobId, onClose, onAddResume }: { jobId: number | null; onClose: () => void; onAddResume: () => void }) {
  useEffect(() => {
    if (jobId == null) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [jobId, onClose])

  return (
    <AnimatePresence>
      {jobId != null && (
        <>
          <motion.div
            key="backdrop"
            className="fixed inset-0 z-40 bg-slate-950/40 backdrop-blur-sm"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
          />
          <motion.aside
            key="drawer"
            role="dialog"
            aria-modal
            className="fixed inset-y-0 right-0 z-50 flex w-full max-w-2xl flex-col border-l border-slate-200 bg-white shadow-2xl dark:border-white/10 dark:bg-ink-900"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 30, stiffness: 260 }}
          >
            <DrawerBody jobId={jobId} onClose={onClose} onAddResume={onAddResume} />
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  )
}

function DrawerBody({ jobId, onClose, onAddResume }: { jobId: number; onClose: () => void; onAddResume: () => void }) {
  const { data: job, isError, error } = useQuery({ queryKey: ['job', jobId], queryFn: () => fetchJob(jobId) })
  const message = useReferralMessage(job)
  return (
    <>
      <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-white/10">
        <span className="text-xs font-medium tracking-wide text-slate-400 uppercase">Role details</span>
        <button onClick={onClose} className="grid h-9 w-9 place-items-center rounded-full text-slate-500 hover:bg-slate-100 dark:hover:bg-white/10" aria-label="Close">
          <X size={18} />
        </button>
      </div>
      <div className="scrollbar-thin flex-1 overflow-y-auto">
        {isError && <p className="p-6 text-sm text-rose-500">{(error as Error).message}</p>}
        {!job && !isError && <DrawerSkeleton />}
        {job && <JobContent job={job} message={message} onAddResume={onAddResume} />}
      </div>
      {job && (
        <div className="border-t border-slate-200 p-4 dark:border-white/10">
          <a
            href={job.url}
            target="_blank"
            rel="noreferrer"
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-cyan-500 py-3 text-sm font-semibold text-white shadow-lg shadow-violet-600/30 transition hover:brightness-110"
          >
            Apply on {job.company}&apos;s site <ArrowUpRight size={16} />
          </a>
          <div className="mt-2.5 flex items-center gap-2">
            <a
              href={connectionsUrl(job.company, job.linkedinCompanyId)}
              target="_blank"
              rel="noreferrer"
              className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-700 transition hover:border-sky-400 hover:text-sky-600 dark:border-white/10 dark:text-slate-200 dark:hover:border-sky-400 dark:hover:text-sky-300"
              title="Opens LinkedIn's search of your 1st-degree connections at this company"
            >
              <Users size={16} /> Find your connections at {job.company}
            </a>
            <a
              href={connectionsUrl(job.company, job.linkedinCompanyId, 'S')}
              target="_blank"
              rel="noreferrer"
              className="shrink-0 rounded-xl border border-slate-200 px-3 py-2.5 text-xs font-semibold text-slate-500 transition hover:border-sky-400 hover:text-sky-600 dark:border-white/10 dark:text-slate-400 dark:hover:text-sky-300"
              title="People your connections know at this company"
            >
              2nd°
            </a>
            {message && <CopyButton text={message} compact />}
          </div>
        </div>
      )}
    </>
  )
}

/** The referral message for this job (the user's template filled from their profile); null = no profile, undefined = loading. */
function useReferralMessage(job: JobDetail | undefined): string | null | undefined {
  const profile = useQuery({ queryKey: ['profile'], queryFn: fetchProfile, staleTime: Infinity })
  const template = useQuery({ queryKey: ['referral-template'], queryFn: fetchReferralTemplate, staleTime: Infinity })
  if (profile.data === null) return null
  if (!job || !profile.data || !template.data) return undefined
  return fillReferral(template.data.text, profile.data, { title: job.title, company: job.company, url: job.url })
}

/** The Clipboard API needs a secure page (https or localhost); plain http (e.g. jobserver.local:8080) gets the old way. */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    try {
      return document.execCommand('copy')
    } catch {
      return false
    } finally {
      area.remove()
    }
  }
}

function CopyButton({ text, compact }: { text: string; compact?: boolean }) {
  const [copied, setCopied] = useState<boolean | null>(null)
  const copy = async () => {
    const ok = await copyText(text)
    setCopied(ok)
    setTimeout(() => setCopied(null), 2000)
  }
  return (
    <button
      type="button"
      onClick={copy}
      title="Copy the referral message"
      className={
        compact
          ? 'flex shrink-0 items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-2.5 text-xs font-semibold text-slate-600 transition hover:border-violet-400 hover:text-violet-600 dark:border-white/10 dark:text-slate-300 dark:hover:text-violet-300'
          : 'flex items-center gap-1.5 rounded-lg bg-violet-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-violet-500'
      }
    >
      {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? 'Copied' : copied === false ? 'Select and copy' : compact ? 'Message' : 'Copy'}
    </button>
  )
}

function ReferralCard({ message, onAddResume }: { message: string | null | undefined; onAddResume: () => void }) {
  if (message === undefined) return null
  return (
    <div className="rounded-2xl border border-slate-200 p-4 dark:border-white/10">
      <div className="flex items-center justify-between gap-3">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-slate-400 uppercase">
          <MessageSquareText size={14} className="text-violet-500" /> Referral message
        </h3>
        {message && <CopyButton text={message} />}
      </div>
      {message ? (
        <>
          <p className="mt-3 text-sm leading-relaxed whitespace-pre-line [overflow-wrap:anywhere] text-slate-700 dark:text-slate-200">{message}</p>
          <p className="mt-2 text-xs text-slate-400">Send it to a connection at the company. Change the wording in My profile.</p>
        </>
      ) : (
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          <button onClick={onAddResume} className="font-semibold text-violet-600 hover:underline dark:text-violet-400">
            Add your resume
          </button>{' '}
          to get a ready message to ask for a referral.
        </p>
      )}
    </div>
  )
}

function JobContent({ job, message, onAddResume }: { job: JobDetail; message: string | null | undefined; onAddResume: () => void }) {
  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="space-y-7 px-6 py-6">
      <div className="flex items-start gap-4">
        <Avatar slug={job.companySlug} name={job.company} size="lg" />
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-500 dark:text-slate-400">{job.company}</p>
          <h2 className="mt-0.5 font-display text-2xl leading-tight font-bold text-slate-900 dark:text-white">{job.title}</h2>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2.5">
        <Fact icon={<MapPin size={15} />} label="Location" value={job.cities.length ? job.cities.join(', ') : job.locations.join('; ') || '—'} />
        <Fact icon={<Briefcase size={15} />} label="Experience" value={years(job.minYears, job.maxYears, job.yearsStated)} />
        <Fact icon={<Building2 size={15} />} label="Family" value={[job.family, ...job.secondaryFamilies].filter(Boolean).map((f) => FAMILY_SHORT[f as string] ?? f).join(' · ') || '—'} />
        <Fact icon={job.remote ? <Globe2 size={15} /> : <Calendar size={15} />} label={job.remote ? 'Remote' : 'Posted'} value={job.remote ? `Yes · posted ${ago(job.postedAt)}` : ago(job.postedAt) || '—'} />
      </div>

      <ReferralCard message={message} onAddResume={onAddResume} />

      {job.yearsEvidence && job.yearsStated && !job.yearsEvidence.startsWith('title:') && (
        <div className="flex gap-3 rounded-2xl border border-violet-500/20 bg-violet-500/[0.06] p-4 text-sm text-slate-700 dark:text-slate-300">
          <Quote size={16} className="mt-0.5 shrink-0 text-violet-500" />
          <span>{job.yearsEvidence.replace(/^[-•*]\s*/, '')}</span>
        </div>
      )}

      {(job.requiredSkills.length > 0 || job.preferredSkills.length > 0) && (
        <div className="space-y-4">
          {job.requiredSkills.length > 0 && <SkillRow title="Asks for" skills={job.requiredSkills} strong />}
          {job.preferredSkills.length > 0 && <SkillRow title="Nice to have" skills={job.preferredSkills} />}
        </div>
      )}

      <div>
        <h3 className="mb-3 text-xs font-semibold tracking-wide text-slate-400 uppercase">From the posting</h3>
        {job.description ? <Description text={job.description} /> : <p className="text-sm text-slate-500">The company&apos;s site has the full description.</p>}
      </div>
    </motion.div>
  )
}

function Fact({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-xl bg-slate-50 px-3.5 py-3 dark:bg-white/[0.04]">
      <p className="flex items-center gap-1.5 text-[11px] font-medium tracking-wide text-slate-400 uppercase">
        <span className="text-violet-500">{icon}</span> {label}
      </p>
      <p className="mt-1 text-sm font-medium text-slate-800 dark:text-slate-200">{value}</p>
    </div>
  )
}

function SkillRow({ title, skills, strong }: { title: string; skills: string[]; strong?: boolean }) {
  return (
    <div>
      <h3 className="mb-2 text-xs font-semibold tracking-wide text-slate-400 uppercase">{title}</h3>
      <div className="flex flex-wrap gap-1.5">
        {skills.map((skill, i) => (
          <motion.span
            key={skill}
            initial={{ opacity: 0, scale: 0.85 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: i * 0.02 }}
            className={
              strong
                ? 'rounded-lg bg-gradient-to-r from-violet-600/90 to-indigo-500/90 px-2.5 py-1 text-xs font-medium text-white'
                : 'rounded-lg bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600 dark:bg-white/[0.06] dark:text-slate-300'
            }
          >
            {skill}
          </motion.span>
        ))}
      </div>
    </div>
  )
}

/** The plain-text description with its structure back: "- " lines become a list, short lines become headings. */
function Description({ text }: { text: string }) {
  const blocks: ReactNode[] = []
  let bullets: string[] = []
  const flush = () => {
    if (bullets.length) {
      blocks.push(
        <ul key={`ul-${blocks.length}`} className="my-2 space-y-1.5">
          {bullets.map((b, i) => (
            <li key={i} className="flex gap-2.5">
              <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-violet-400" />
              <span>{b}</span>
            </li>
          ))}
        </ul>,
      )
      bullets = []
    }
  }
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) {
      flush()
      continue
    }
    const bullet = line.match(/^(?:[-•●*➢✓]|\d+[.)])\s+(.*)$/)
    if (bullet) {
      bullets.push(bullet[1])
      continue
    }
    flush()
    const heading = line.length <= 60 && !/[.;,]$/.test(line) && !/[.;]\s/.test(line)
    blocks.push(
      heading ? (
        <h4 key={blocks.length} className="mt-5 mb-1 font-display text-[15px] font-semibold text-slate-900 dark:text-white">
          {line.replace(/:$/, '')}
        </h4>
      ) : (
        <p key={blocks.length} className="my-2">
          {line}
        </p>
      ),
    )
  }
  flush()
  return <div className="text-[14.5px] leading-relaxed text-slate-600 dark:text-slate-300">{blocks}</div>
}

function DrawerSkeleton() {
  return (
    <div className="space-y-5 p-6">
      <div className="flex gap-4">
        <div className="h-14 w-14 animate-pulse rounded-xl bg-slate-200 dark:bg-white/10" />
        <div className="flex-1 space-y-2">
          <div className="h-3 w-24 animate-pulse rounded bg-slate-200 dark:bg-white/10" />
          <div className="h-6 w-3/4 animate-pulse rounded bg-slate-200 dark:bg-white/10" />
        </div>
      </div>
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="h-3 animate-pulse rounded bg-slate-200 dark:bg-white/10" style={{ width: `${90 - (i % 3) * 15}%` }} />
      ))}
    </div>
  )
}
