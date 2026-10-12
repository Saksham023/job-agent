import { AnimatePresence, motion } from 'framer-motion'
import clsx from 'clsx'
import { Check, Eye, EyeOff, MoreHorizontal, X } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { JOB_STATUSES, type JobStatus } from '../lib/api'
import { dismissToast, hiddenChange, STATUS, statusChange, useSetMarks, useToast, type Marks } from '../lib/status'

type Job = { id: number; title: string } & Marks

/** The status a job carries and whether it is hidden, as small chips (nothing when it has neither). */
export function StatusChip({ status, hidden }: Marks) {
  return (
    <>
      {status && <Chip mark={status} />}
      {hidden && <Chip mark="HIDDEN" />}
    </>
  )
}

function Chip({ mark }: { mark: keyof typeof STATUS }) {
  const { label, icon: Icon, chip } = STATUS[mark]
  return (
    <span className={clsx('inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-[11px] font-semibold', chip)}>
      <Icon size={11} /> {label}
    </span>
  )
}

/**
 * The "…" button on a job card and its menu. The menu is drawn in a layer over the page (the card clips its content),
 * placed under the button, or above it when there is no room below. It follows the button when the page scrolls and
 * closes on a choice, Esc, a click outside, or when the button scrolls out of view.
 */
export function StatusMenu({ job }: { job: Job }) {
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const [place, setPlace] = useState<{ top: number; left: number } | null>(null)
  const set = useSetMarks()

  /** Puts the menu under the button (above when there is no room); closes it once the button is out of view. */
  const position = () => {
    if (!button.current) return
    const r = button.current.getBoundingClientRect()
    if (r.bottom < 0 || r.top > window.innerHeight) {
      setOpen(false)
      return
    }
    const width = 208
    const height = 270
    const below = r.bottom + 6 + height < window.innerHeight
    setPlace({
      top: below ? r.bottom + 6 : r.top - 6 - height,
      left: Math.max(8, Math.min(r.right - width, window.innerWidth - width - 8)),
    })
  }

  useLayoutEffect(() => {
    if (open) position()
  }, [open])

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !menu.current?.contains(e.target as Node) && !button.current?.contains(e.target as Node)) {
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

  const choose = (status: JobStatus | null) => {
    setOpen(false)
    if (status !== job.status) set.mutate(statusChange(job, status))
  }

  return (
    <>
      <button
        ref={button}
        onClick={(e) => {
          e.stopPropagation()
          setOpen(!open)
        }}
        aria-label="Mark this job"
        aria-haspopup="menu"
        aria-expanded={open}
        className={clsx(
          'grid h-8 w-8 shrink-0 place-items-center rounded-full border transition',
          open
            ? 'border-violet-400 bg-violet-500/10 text-violet-600 dark:text-violet-300'
            : 'border-slate-200 text-slate-500 hover:border-violet-400 hover:text-violet-600 dark:border-white/10 dark:text-slate-400 dark:hover:text-violet-300',
        )}
      >
        <MoreHorizontal size={16} />
      </button>
      {createPortal(
        <AnimatePresence>
          {open && place && (
            <motion.div
              ref={menu}
              role="menu"
              onClick={(e) => e.stopPropagation()}
              initial={{ opacity: 0, scale: 0.96 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.96 }}
              transition={{ duration: 0.12 }}
              style={{ top: place.top, left: place.left }}
              className="fixed z-[60] w-52 rounded-2xl border border-slate-200/80 bg-white p-1.5 shadow-xl shadow-slate-900/10 dark:border-white/10 dark:bg-ink-900 dark:shadow-2xl dark:shadow-black/60"
            >
              <p className="px-2.5 pt-1 pb-1.5 text-[11px] font-semibold tracking-wide text-slate-400 uppercase">Mark as</p>
              {JOB_STATUSES.map((status) => {
                const { label, icon: Icon } = STATUS[status]
                const active = job.status === status
                return (
                  <button
                    key={status}
                    role="menuitemradio"
                    aria-checked={active}
                    onClick={() => choose(active ? null : status)}
                    className={clsx(
                      'flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm transition',
                      active ? 'bg-violet-500/10 text-violet-700 dark:text-violet-200' : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5',
                    )}
                  >
                    <Icon size={15} className="shrink-0" />
                    <span className="flex-1">{label}</span>
                    {active && <Check size={14} />}
                  </button>
                )
              })}
              {job.status && (
                <button
                  role="menuitem"
                  onClick={() => choose(null)}
                  className="flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm text-slate-500 transition hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-white/5"
                >
                  <X size={15} /> Clear status
                </button>
              )}
              <div className="my-1 h-px bg-slate-200/70 dark:bg-white/10" />
              <button
                role="menuitem"
                onClick={() => {
                  setOpen(false)
                  set.mutate(hiddenChange(job, !job.hidden))
                }}
                className="flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
              >
                {job.hidden ? <Eye size={15} /> : <EyeOff size={15} />} {job.hidden ? 'Unhide' : 'Hide'}
              </button>
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </>
  )
}

/**
 * One click "Hide" in the card's corner (the action used most, e.g. right after applying); the job keeps its status.
 * On a hidden job (the "Hidden" view) it shows the job again. The statuses stay in the "..." menu.
 */
export function HideButton({ job }: { job: Job }) {
  const set = useSetMarks()
  const hidden = job.hidden
  const label = hidden ? 'Unhide' : 'Hide'
  return (
    <button
      onClick={(e) => {
        e.stopPropagation()
        set.mutate(hiddenChange(job, !hidden))
      }}
      aria-label={label}
      title={label}
      className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-slate-400 transition hover:bg-rose-500/10 hover:text-rose-500 dark:text-slate-500 dark:hover:text-rose-400"
    >
      {hidden ? <Eye size={16} /> : <EyeOff size={16} />}
    </button>
  )
}

/** The three statuses and Hide as buttons (the job drawer); clicking an active one clears it. */
export function StatusButtons({ job }: { job: Job }) {
  const set = useSetMarks()
  return (
    <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-4" role="group" aria-label="Your marks for this job">
      {JOB_STATUSES.map((status) => {
        const { label, icon: Icon } = STATUS[status]
        const active = job.status === status
        return (
          <button
            key={status}
            aria-pressed={active}
            onClick={() => set.mutate(statusChange(job, active ? null : status))}
            className={clsx(
              'flex items-center justify-center gap-1.5 rounded-xl border px-2 py-2 text-xs font-semibold transition',
              active
                ? 'border-violet-500 bg-violet-500/10 text-violet-700 dark:text-violet-200'
                : 'border-slate-200 text-slate-600 hover:border-violet-400 hover:text-violet-600 dark:border-white/10 dark:text-slate-300 dark:hover:text-violet-300',
            )}
          >
            <Icon size={14} className="shrink-0" /> <span className="truncate">{label}</span>
          </button>
        )
      })}
      <button
        aria-pressed={job.hidden}
        onClick={() => set.mutate(hiddenChange(job, !job.hidden))}
        className={clsx(
          'flex items-center justify-center gap-1.5 rounded-xl border px-2 py-2 text-xs font-semibold transition',
          job.hidden
            ? 'border-violet-500 bg-violet-500/10 text-violet-700 dark:text-violet-200'
            : 'border-slate-200 text-slate-600 hover:border-violet-400 hover:text-violet-600 dark:border-white/10 dark:text-slate-300 dark:hover:text-violet-300',
        )}
      >
        <EyeOff size={14} className="shrink-0" /> <span className="truncate">{job.hidden ? 'Hidden' : 'Hide'}</span>
      </button>
    </div>
  )
}

/** "Hidden … · Undo" after a job leaves the open list, or an error when the server refused a change. */
export function StatusToast() {
  const toast = useToast()
  const set = useSetMarks()
  return createPortal(
    <AnimatePresence>
      {toast && (
        <motion.div
          key={toast.id}
          role="status"
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 16 }}
          className="fixed inset-x-4 bottom-20 z-[70] mx-auto flex max-w-md items-center gap-3 rounded-2xl bg-slate-900 px-4 py-3 text-sm text-white shadow-2xl lg:bottom-6 dark:bg-white dark:text-slate-900"
        >
          <span className="min-w-0 flex-1 truncate">{toast.text}</span>
          {toast.undo && (
            <button
              onClick={() => {
                set.mutate(toast.undo!)
                dismissToast()
              }}
              className="shrink-0 font-semibold text-violet-300 hover:underline dark:text-violet-600"
            >
              Undo
            </button>
          )}
          <button onClick={dismissToast} aria-label="Dismiss" className="shrink-0 opacity-60 hover:opacity-100">
            <X size={15} />
          </button>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  )
}
