import clsx from 'clsx'
import { RotateCw } from 'lucide-react'
import { useIsFetching, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

/**
 * Fetches the newest jobs from our database again (no crawl): the open job list keeps its filters and its place, and
 * the filter counts, the company strip and "Updated ... ago" follow. The icon spins while anything is loading.
 */
export function RefreshButton() {
  const client = useQueryClient()
  const loading = useIsFetching({ predicate: (q) => ['jobs', 'facets', 'meta', 'job'].includes(q.queryKey[0] as string) }) > 0
  const [clicked, setClicked] = useState(false)
  const spinning = loading || clicked

  const refresh = async () => {
    if (spinning) return
    setClicked(true)
    const minimum = new Promise((resolve) => setTimeout(resolve, 600)) // a spin short enough to notice, even when the answer is instant
    await Promise.allSettled([
      client.invalidateQueries({ queryKey: ['jobs'] }),
      client.invalidateQueries({ queryKey: ['facets'] }),
      client.invalidateQueries({ queryKey: ['meta'] }),
      client.invalidateQueries({ queryKey: ['job'] }),
      minimum,
    ])
    setClicked(false)
  }

  return (
    <button
      onClick={refresh}
      disabled={spinning}
      title="Show the latest jobs"
      aria-label="Show the latest jobs"
      className="glass grid h-10 w-10 place-items-center rounded-full text-slate-600 transition hover:scale-105 hover:text-slate-900 disabled:cursor-default disabled:hover:scale-100 dark:text-slate-300 dark:hover:text-white"
    >
      <RotateCw size={17} className={clsx(spinning && 'animate-spin')} />
    </button>
  )
}
