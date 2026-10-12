import { RotateCw } from 'lucide-react'
import { useIsFetching, useQueryClient } from '@tanstack/react-query'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'

/** One full turn while loading takes this long (milliseconds). */
const TURN = 800

/**
 * Fetches the newest jobs from our database again (no crawl): the open job list keeps its filters and its place, and
 * the filter counts, the company strip and "Updated ... ago" follow. The icon turns while anything is loading; when
 * loading ends it does not snap back but eases out and stops at the end of the turn it is in, upright again.
 *
 * Both the turning and the stop are Web Animations on `transform` only, which the browser runs off the main thread:
 * they stay smooth while the page is busy building the new job list (a JavaScript-driven angle stuttered there).
 */
export function RefreshButton() {
  const client = useQueryClient()
  const loading = useIsFetching({ predicate: (q) => ['jobs', 'facets', 'meta', 'job'].includes(q.queryKey[0] as string) }) > 0
  const [clicked, setClicked] = useState(false)
  const spinning = loading || clicked
  const icon = useRef<HTMLSpanElement>(null)
  const turning = useRef<Animation | null>(null)
  const stopping = useRef<Animation | null>(null)

  useLayoutEffect(() => {
    const element = icon.current
    if (!element || typeof element.animate !== 'function') return
    const from = angle(element)
    if (spinning) {
      if (turning.current) return
      stopping.current?.cancel()
      stopping.current = null
      turning.current = element.animate(
        [{ transform: `rotate(${from}deg)` }, { transform: `rotate(${from + 360}deg)` }],
        { duration: TURN, iterations: Infinity, easing: 'linear' },
      )
      return
    }
    if (!turning.current) return
    turning.current.cancel()
    turning.current = null
    // finish the turn in hand, slowing down into the upright position
    const left = 360 - from
    if (left < 1) return
    stopping.current = element.animate([{ transform: `rotate(${from}deg)` }, { transform: 'rotate(360deg)' }], {
      duration: Math.min(1000, Math.max(350, (left / 360) * TURN * 1.6)),
      easing: 'cubic-bezier(0.2, 0.6, 0.35, 1)',
    })
    stopping.current.onfinish = () => {
      stopping.current = null
    }
  }, [spinning])

  useEffect(
    () => () => {
      turning.current?.cancel()
      stopping.current?.cancel()
    },
    [],
  )

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
      <span ref={icon} className="grid place-items-center will-change-transform">
        <RotateCw size={17} />
      </span>
    </button>
  )
}

/** The element's current rotation in degrees (0 to 360), read from the running animation's transform. */
function angle(element: HTMLElement): number {
  const transform = getComputedStyle(element).transform
  if (!transform || transform === 'none') return 0
  const [a, b] = transform.slice(transform.indexOf('(') + 1, -1).split(',').map(Number)
  const degrees = (Math.atan2(b, a) * 180) / Math.PI
  return degrees < 0 ? degrees + 360 : degrees
}
