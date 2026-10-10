import { AnimatePresence, motion } from 'framer-motion'
import { ArrowRight, Eye, EyeOff, Loader2, Lock, Mail, Radar } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import clsx from 'clsx'
import { signIn, signUp } from '../lib/auth'
import { ThemeToggle } from './ThemeToggle'

type Mode = 'signin' | 'signup'

/** The whole app sits behind this: sign in, or create an account. */
export function AuthScreen() {
  const [mode, setMode] = useState<Mode>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const switchTo = (next: Mode) => {
    setMode(next)
    setError(null)
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (mode === 'signup' && password.length < 8) {
      setError('The password needs at least 8 characters')
      return
    }
    setBusy(true)
    try {
      await (mode === 'signin' ? signIn(email, password) : signUp(email, password))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="aurora relative flex min-h-screen flex-col overflow-x-clip">
      <div className="grid-bg pointer-events-none absolute inset-0" />
      <div className="relative flex justify-end p-4 sm:p-6">
        <ThemeToggle />
      </div>

      <main className="relative flex flex-1 items-center justify-center px-4 pb-16">
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="w-full max-w-sm"
        >
          <div className="mb-8 flex flex-col items-center text-center">
            <span className="grid h-12 w-12 place-items-center rounded-2xl bg-gradient-to-br from-violet-500 to-cyan-500 text-white shadow-lg shadow-violet-500/30">
              <Radar size={24} />
            </span>
            <h1 className="mt-4 font-display text-2xl font-semibold tracking-tight text-slate-900 dark:text-white">
              Job<span className="text-gradient">Radar</span>
            </h1>
            <p className="mt-1.5 text-sm text-slate-500 dark:text-slate-400">
              Live tech openings from India&apos;s top companies, in one place.
            </p>
          </div>

          <div className="glass rounded-3xl p-6 shadow-xl shadow-slate-900/5 sm:p-7">
            <div className="mb-6 grid grid-cols-2 rounded-full bg-slate-100 p-1 text-sm font-medium dark:bg-white/5" role="tablist">
              {(['signin', 'signup'] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  role="tab"
                  aria-selected={mode === m}
                  onClick={() => switchTo(m)}
                  className="relative rounded-full py-2 transition"
                >
                  {mode === m && (
                    <motion.span
                      layoutId="auth-tab"
                      className="absolute inset-0 rounded-full bg-white shadow-sm dark:bg-white/10"
                      transition={{ type: 'spring', bounce: 0.2, duration: 0.4 }}
                    />
                  )}
                  <span className={clsx('relative', mode === m ? 'text-slate-900 dark:text-white' : 'text-slate-500 dark:text-slate-400')}>
                    {m === 'signin' ? 'Sign in' : 'Create account'}
                  </span>
                </button>
              ))}
            </div>

            <form onSubmit={submit} className="space-y-4" noValidate>
              <label className="block">
                <span className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">Email</span>
                <span className="relative block">
                  <Mail size={16} className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-slate-400" />
                  <input
                    type="email"
                    autoComplete="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    className="w-full rounded-xl border border-slate-200 bg-white/80 py-2.5 pr-3 pl-10 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/15 dark:border-white/10 dark:bg-white/5 dark:text-white"
                  />
                </span>
              </label>

              <label className="block">
                <span className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">Password</span>
                <span className="relative block">
                  <Lock size={16} className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-slate-400" />
                  <input
                    type={show ? 'text' : 'password'}
                    autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder={mode === 'signup' ? 'At least 8 characters' : 'Your password'}
                    className="w-full rounded-xl border border-slate-200 bg-white/80 py-2.5 pr-10 pl-10 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/15 dark:border-white/10 dark:bg-white/5 dark:text-white"
                  />
                  <button
                    type="button"
                    onClick={() => setShow(!show)}
                    className="absolute top-1/2 right-2.5 -translate-y-1/2 rounded-md p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                    aria-label={show ? 'Hide password' : 'Show password'}
                  >
                    {show ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </span>
              </label>

              <AnimatePresence>
                {error && (
                  <motion.p
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    exit={{ opacity: 0, height: 0 }}
                    role="alert"
                    className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-400"
                  >
                    {error}
                  </motion.p>
                )}
              </AnimatePresence>

              <button
                type="submit"
                disabled={busy || !email || !password}
                className="group flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-fuchsia-500 py-2.5 text-sm font-semibold text-white shadow-lg shadow-violet-500/25 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {busy ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : (
                  <>
                    {mode === 'signin' ? 'Sign in' : 'Create account'}
                    <ArrowRight size={16} className="transition group-hover:translate-x-0.5" />
                  </>
                )}
              </button>
            </form>
          </div>

          <p className="mt-6 text-center text-xs text-slate-400">
            {mode === 'signin' ? 'New here? ' : 'Already have an account? '}
            <button
              type="button"
              onClick={() => switchTo(mode === 'signin' ? 'signup' : 'signin')}
              className="font-medium text-violet-600 hover:underline dark:text-violet-400"
            >
              {mode === 'signin' ? 'Create an account' : 'Sign in'}
            </button>
          </p>
        </motion.div>
      </main>
    </div>
  )
}
