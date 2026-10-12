import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { Bookmark, EyeOff, Handshake, Send, type LucideIcon } from 'lucide-react'
import { useSyncExternalStore } from 'react'
import { setJobHidden, setJobStatus, type Filters, type JobDetail, type JobPage, type JobStatus, type StatusFilter } from './api'

// The signed-in user's marks per job: a status (Saved, Applied, Referral asked) and, separately, "hidden" (left out of
// the normal search; a job can be Applied AND hidden). A change updates every loaded job list at once (a hidden job
// disappears from lists that leave it out), then the server is told; if the server says no, the lists are put back.
// A job that leaves the open list shows a short "Undo".

export const STATUS: Record<StatusFilter, { label: string; icon: LucideIcon; chip: string }> = {
  SAVED: { label: 'Saved', icon: Bookmark, chip: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  APPLIED: { label: 'Applied', icon: Send, chip: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300' },
  REFERRAL_ASKED: { label: 'Referral asked', icon: Handshake, chip: 'bg-sky-500/15 text-sky-700 dark:text-sky-300' },
  HIDDEN: { label: 'Hidden', icon: EyeOff, chip: 'bg-slate-500/15 text-slate-600 dark:text-slate-300' },
}

/** A job's marks. */
export type Marks = { status: JobStatus | null; hidden: boolean }

/** Whether a job with these marks belongs in a list with these filters (the server's rule, see JobSearch). */
export function shown(filters: Filters, marks: Marks): boolean {
  if (filters.statuses.length > 0) {
    return (marks.status != null && filters.statuses.includes(marks.status)) || (marks.hidden && filters.statuses.includes('HIDDEN'))
  }
  return !marks.hidden && !(marks.status != null && (filters.hidden ?? []).includes(marks.status))
}

type Pages = InfiniteData<JobPage>
type Change = { jobId: number; title: string; to: Marks; from: Marks; undone?: boolean }

/** Changes a job's status and/or hidden flag, updating the loaded lists right away. */
export function useSetMarks() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (change: Change) => {
      if (change.to.status !== change.from.status) await setJobStatus(change.jobId, change.to.status)
      if (change.to.hidden !== change.from.hidden) await setJobHidden(change.jobId, change.to.hidden)
    },
    onMutate: async (change) => {
      await client.cancelQueries({ queryKey: ['jobs'] })
      const lists = client.getQueriesData<Pages>({ queryKey: ['jobs'] })
      const detail = client.getQueryData<JobDetail>(['job', change.jobId])
      for (const [key, data] of lists) {
        if (!data) continue
        const filters = key[1] as Filters
        const keep = shown(filters, change.to)
        const had = data.pages.some((page) => page.jobs.some((job) => job.id === change.jobId))
        client.setQueryData<Pages>(key, {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            total: had && !keep ? page.total - 1 : page.total,
            jobs: keep
              ? page.jobs.map((job) => (job.id === change.jobId ? { ...job, ...change.to } : job))
              : page.jobs.filter((job) => job.id !== change.jobId),
          })),
        })
      }
      if (detail) client.setQueryData<JobDetail>(['job', change.jobId], { ...detail, ...change.to })
      // a job that leaves the list it was in (hidden, or a status hidden for now) can be brought back
      const left = lists.some(([key, data]) => {
        const filters = key[1] as Filters
        return !shown(filters, change.to) && !!data?.pages.some((p) => p.jobs.some((j) => j.id === change.jobId))
      })
      if (left && !change.undone) showUndo(change)
      return { lists, detail }
    },
    onError: (error, change, saved) => {
      saved?.lists.forEach(([key, data]) => client.setQueryData(key, data))
      if (saved?.detail) client.setQueryData(['job', change.jobId], saved.detail)
      showError(`Couldn't update "${change.title}": ${(error as Error).message}`)
    },
    onSettled: (_data, _error, change) => {
      void client.invalidateQueries({ queryKey: ['facets'] })
      // lists showing other marks refetch when next shown; a job shown again (unhidden, or brought back by Undo) is
      // fetched into the open list now
      void client.invalidateQueries({ queryKey: ['jobs'], refetchType: change.from.hidden || change.undone ? 'active' : 'none' })
    },
  })
}

/** The change that sets (or, with null, clears) a job's status and keeps its hidden flag. */
export const statusChange = (job: { id: number; title: string } & Marks, status: JobStatus | null): Change => ({
  jobId: job.id,
  title: job.title,
  from: { status: job.status, hidden: job.hidden },
  to: { status, hidden: job.hidden },
})

/** The change that hides a job (or shows it again) and keeps its status. */
export const hiddenChange = (job: { id: number; title: string } & Marks, hidden: boolean): Change => ({
  jobId: job.id,
  title: job.title,
  from: { status: job.status, hidden: job.hidden },
  to: { status: job.status, hidden },
})

// ---------------------------------------------------------------- the toast ("Hidden · Undo", or an error)

export type Toast = { id: number; text: string; undo?: Change } | null

let toast: Toast = null
let timer: ReturnType<typeof setTimeout> | undefined
const listeners = new Set<() => void>()

function setToast(next: Toast) {
  toast = next
  clearTimeout(timer)
  if (next) timer = setTimeout(() => setToast(null), next.undo ? 6000 : 5000)
  listeners.forEach((listener) => listener())
}

function showUndo(change: Change) {
  const text =
    change.to.hidden && !change.from.hidden
      ? `Hidden “${change.title}”`
      : !change.to.hidden && change.from.hidden
        ? `“${change.title}” is back in your search`
        : change.to.status != null
          ? `Marked “${change.title}” as ${STATUS[change.to.status].label}`
          : `Cleared the status of “${change.title}”`
  setToast({ id: Date.now(), text, undo: { ...change, to: change.from, from: change.to, undone: true } })
}

function showError(text: string) {
  setToast({ id: Date.now(), text })
}

export const dismissToast = () => setToast(null)

export function useToast(): Toast {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => toast,
  )
}
