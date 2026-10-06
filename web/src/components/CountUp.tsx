import { animate, useInView, useMotionValue, useTransform, motion } from 'framer-motion'
import { useEffect, useRef } from 'react'

/** A number that counts up when it scrolls into view. */
export function CountUp({ value, duration = 1.2 }: { value: number; duration?: number }) {
  const ref = useRef<HTMLSpanElement>(null)
  const inView = useInView(ref, { once: true })
  const count = useMotionValue(0)
  const text = useTransform(count, (v) => Math.round(v).toLocaleString('en-IN'))
  useEffect(() => {
    if (!inView) return
    const controls = animate(count, value, { duration, ease: [0.16, 1, 0.3, 1] })
    return () => controls.stop()
  }, [inView, value, duration, count])
  return <motion.span ref={ref}>{text}</motion.span>
}
