import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { Bookmark, EyeOff, Handshake, Send, type LucideIcon } from 'lucide-react'
import { useSyncExternalStore } from 'react'
import { setJobStatus, type Filters, type JobDetail, type JobPage, type JobStatus } from './api'

// The signed-in user's status per job (Saved, Applied, Referral asked, Not interested). Setting one updates every
// loaded job list at once (a "Not interested" job disappears from lists that hide it), then the server is told; if the
// server says no, the lists are put back. Hiding a job shows a short "Undo".

export const STATUS: Record<JobStatus, { label: string; icon: LucideIcon; chip: string }> = {
  SAVED: { label: 'Saved', icon: Bookmark, chip: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  APPLIED: { label: 'Applied', icon: Send, chip: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300' },
  REFERRAL_ASKED: { label: 'Referral asked', icon: Handshake, chip: 'bg-sky-500/15 text-sky-700 dark:text-sky-300' },
  NOT_INTERESTED: { label: 'Not interested', icon: EyeOff, chip: 'bg-slate-500/15 text-slate-600 dark:text-slate-300' },
}

/** Whether a job with this status belongs in a list with these filters (the server's rule, see JobSearch). */
export function shown(filters: Filters, status: JobStatus | null): boolean {
  if (filters.statuses.length > 0) return status != null && filters.statuses.includes(status)
  return status !== 'NOT_INTERESTED' && !(status != null && (filters.hidden ?? []).includes(status))
}

type Pages = InfiniteData<JobPage>
type Change = { jobId: number; title: string; status: JobStatus | null; before: JobStatus | null; undone?: boolean }

/** Sets a job's status (null clears it), updating the loaded lists right away. */
export function useSetStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (change: Change) => setJobStatus(change.jobId, change.status),
    onMutate: async (change) => {
      await client.cancelQueries({ queryKey: ['jobs'] })
      const lists = client.getQueriesData<Pages>({ queryKey: ['jobs'] })
      const detail = client.getQueryData<JobDetail>(['job', change.jobId])
      for (const [key, data] of lists) {
        if (!data) continue
        const filters = key[1] as Filters
        const keep = shown(filters, change.status)
        const had = data.pages.some((page) => page.jobs.some((job) => job.id === change.jobId))
        client.setQueryData<Pages>(key, {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            total: had && !keep ? page.total - 1 : page.total,
            jobs: keep
              ? page.jobs.map((job) => (job.id === change.jobId ? { ...job, status: change.status } : job))
              : page.jobs.filter((job) => job.id !== change.jobId),
          })),
        })
      }
      if (detail) client.setQueryData<JobDetail>(['job', change.jobId], { ...detail, status: change.status })
      // a job that leaves the list it was in (not interested, or a status hidden for now) can be brought back
      const left = lists.some(([key, data]) => {
        const filters = key[1] as Filters
        return !shown(filters, change.status) && !!data?.pages.some((p) => p.jobs.some((j) => j.id === change.jobId))
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
      // lists showing other statuses refetch when next shown; an undone hide brings the job back into this list now
      // (a job brought back by Undo, or whose status moved into what the open list shows, is fetched again)
      void client.invalidateQueries({ queryKey: ['jobs'], refetchType: change.before === 'NOT_INTERESTED' || change.undone ? 'active' : 'none' })
    },
  })
}

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
    change.status === 'NOT_INTERESTED' || change.status == null && change.before == null
      ? `Hidden “${change.title}”`
      : change.status == null
        ? `“${change.title}” is back in your search`
        : `Marked “${change.title}” as ${STATUS[change.status].label}`
  setToast({ id: Date.now(), text, undo: { ...change, status: change.before, before: change.status, undone: true } })
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
