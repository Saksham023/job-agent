import { AnimatePresence, motion } from 'framer-motion'
import { FileText, LogOut, MonitorSmartphone, ShieldCheck, UserRound } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { signOut, type User } from '../lib/auth'

/** The signed-in user's initial; opens their email, role and the sign-out actions. */
export function UserMenu({ user, onProfile, onResume }: { user: User; onProfile: () => void; onResume: () => void }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
    }
  }, [open])

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(!open)}
        aria-label="Account"
        aria-expanded={open}
        className="grid h-10 w-10 place-items-center rounded-full bg-gradient-to-br from-violet-500 to-cyan-500 font-display text-sm font-semibold text-white uppercase shadow-md shadow-violet-500/25 transition hover:scale-105"
      >
        {user.email.charAt(0)}
      </button>
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -6, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.97 }}
            transition={{ duration: 0.15 }}
            className="glass absolute right-0 mt-2 w-64 origin-top-right rounded-2xl p-2 shadow-xl shadow-slate-900/10"
          >
            <div className="px-3 py-2">
              <p className="truncate text-sm font-medium text-slate-900 dark:text-white">{user.email}</p>
              {user.role === 'ADMIN' ? (
                <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-violet-500/15 px-2 py-0.5 text-[11px] font-semibold text-violet-600 dark:text-violet-300">
                  <ShieldCheck size={12} /> Admin
                </span>
              ) : (
                <span className="mt-1 inline-block text-xs text-slate-500 dark:text-slate-400">Member</span>
              )}
            </div>
            <div className="my-1 h-px bg-slate-200/70 dark:bg-white/10" />
            <button
              onClick={() => { setOpen(false); onProfile() }}
              className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
            >
              <UserRound size={15} /> My profile
            </button>
            <button
              onClick={() => { setOpen(false); onResume() }}
              className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
            >
              <FileText size={15} /> Update resume
            </button>
            <div className="my-1 h-px bg-slate-200/70 dark:bg-white/10" />
            <button
              onClick={() => void signOut()}
              className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
            >
              <LogOut size={15} /> Sign out
            </button>
            <button
              onClick={() => void signOut(true)}
              className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm text-slate-700 transition hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
            >
              <MonitorSmartphone size={15} /> Sign out on all devices
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
