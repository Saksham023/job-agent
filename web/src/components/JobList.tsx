import { useInfiniteQuery } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { Loader2, SearchX } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { fetchJobs, type Filters, type JobPage } from '../lib/api'
import { JobCard } from './JobCard'
import { CardSkeleton } from './Skeleton'

/** The result cards; the next page loads by itself when the end of the list scrolls into view. */
export function JobList({
  filters,
  onOpen,
  onTotal,
  onReset,
}: {
  filters: Filters
  onOpen: (id: number) => void
  onTotal: (total: number | undefined, loading: boolean) => void
  onReset: () => void
}) {
  const query = useInfiniteQuery<JobPage>({
    queryKey: ['jobs', filters],
    queryFn: ({ pageParam }) => fetchJobs(filters, pageParam as number),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
    placeholderData: (previous) => previous,
  })
  const total = query.data?.pages[0]?.total
  useEffect(() => onTotal(total, query.isFetching && !query.isFetchingNextPage), [total, query.isFetching, query.isFetchingNextPage, onTotal])

  const sentinel = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const node = sentinel.current
    if (!node) return
    const observer = new IntersectionObserver(
      (entries) => entries[0].isIntersecting && query.hasNextPage && !query.isFetchingNextPage && query.fetchNextPage(),
      { rootMargin: '600px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [query])

  if (query.isError) {
    return (
      <div className="glass rounded-2xl p-10 text-center">
        <p className="font-medium text-rose-500">Could not load jobs</p>
        <p className="mt-1 text-sm text-slate-500">{(query.error as Error).message}</p>
        <button onClick={() => query.refetch()} className="mt-4 rounded-full bg-violet-600 px-4 py-2 text-sm font-semibold text-white">
          Try again
        </button>
      </div>
    )
  }

  if (!query.data) {
    return (
      <div className="grid gap-4 lg:grid-cols-2">
        {Array.from({ length: 6 }, (_, i) => <CardSkeleton key={i} />)}
      </div>
    )
  }

  const jobs = query.data.pages.flatMap((page) => page.jobs)
  if (jobs.length === 0) {
    return (
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="glass rounded-3xl px-6 py-16 text-center">
        <div className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-gradient-to-br from-violet-500/20 to-cyan-500/20">
          <SearchX className="text-violet-500" size={30} />
        </div>
        <h3 className="mt-5 font-display text-xl font-semibold text-slate-900 dark:text-white">No roles match all of that</h3>
        <p className="mx-auto mt-2 max-w-sm text-sm text-slate-500 dark:text-slate-400">
          Try a wider experience range, fewer skills, or another city. Remote roles are a filter of their own.
        </p>
        <button onClick={onReset} className="mt-6 rounded-full bg-gradient-to-r from-violet-600 to-cyan-500 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-violet-600/30">
          Clear all filters
        </button>
      </motion.div>
    )
  }

  return (
    <div>
      <motion.div layout className="grid gap-4 lg:grid-cols-2">
        <AnimatePresence mode="popLayout">
          {jobs.map((job, i) => (
            <JobCard key={job.id} job={job} index={i} onOpen={() => onOpen(job.id)} />
          ))}
        </AnimatePresence>
      </motion.div>
      <div ref={sentinel} className="flex h-24 items-center justify-center text-sm text-slate-400">
        {query.isFetchingNextPage ? (
          <Loader2 className="animate-spin text-violet-500" />
        ) : query.hasNextPage ? (
          ''
        ) : (
          `That's all ${jobs.length.toLocaleString('en-IN')} roles`
        )}
      </div>
    </div>
  )
}
