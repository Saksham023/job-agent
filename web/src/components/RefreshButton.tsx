import { RotateCw } from 'lucide-react'
import { useIsFetching, useQueryClient } from '@tanstack/react-query'
import { animate, motion, useMotionValue, type AnimationPlaybackControls } from 'framer-motion'
import { useEffect, useRef, useState } from 'react'

/** One full turn while loading takes this long (seconds). */
const TURN = 0.8

/**
 * Fetches the newest jobs from our database again (no crawl): the open job list keeps its filters and its place, and
 * the filter counts, the company strip and "Updated ... ago" follow. The icon turns while anything is loading; when
 * loading ends it does not snap back but eases out and stops at the end of the turn it is in, upright again.
 */
export function RefreshButton() {
  const client = useQueryClient()
  const loading = useIsFetching({ predicate: (q) => ['jobs', 'facets', 'meta', 'job'].includes(q.queryKey[0] as string) }) > 0
  const [clicked, setClicked] = useState(false)
  const spinning = loading || clicked
  const rotate = useMotionValue(0)
  const turning = useRef<AnimationPlaybackControls | null>(null)

  useEffect(() => {
    if (spinning) {
      if (turning.current) return
      const from = rotate.get() % 360
      rotate.set(from)
      turning.current = animate(rotate, from + 360, { duration: TURN, ease: 'linear', repeat: Infinity })
      return
    }
    if (!turning.current) return
    turning.current.stop()
    turning.current = null
    // finish the turn in hand, slowing down into the upright position
    const now = rotate.get()
    const end = Math.ceil(now / 360) * 360
    const left = end - now
    if (left > 0.5) {
      animate(rotate, end, { duration: Math.min(1, Math.max(0.35, (left / 360) * TURN * 1.6)), ease: [0.2, 0.6, 0.35, 1] })
    }
  }, [spinning, rotate])

  useEffect(() => () => turning.current?.stop(), [])

  const refresh = async () => {
    if (spinning) return
    setClicked(true)
    await Promise.allSettled([
      client.invalidateQueries({ queryKey: ['jobs'] }),
      client.invalidateQueries({ queryKey: ['facets'] }),
      client.invalidateQueries({ queryKey: ['meta'] }),
      client.invalidateQueries({ queryKey: ['job'] }),
    ])
    setClicked(false)
  }

  return (
    <button
      onClick={refresh}
      disabled={spinning}
      title="Show the latest jobs"
      aria-label="Show the latest jobs"
      aria-busy={spinning}
      className="glass grid h-10 w-10 place-items-center rounded-full text-slate-600 transition hover:scale-105 hover:text-slate-900 disabled:cursor-default disabled:hover:scale-100 dark:text-slate-300 dark:hover:text-white"
    >
      <motion.span style={{ rotate }} className="grid place-items-center">
        <RotateCw size={17} />
      </motion.span>
    </button>
  )
}
