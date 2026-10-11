import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { Bell, Check, Send, Unlink, X } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import clsx from 'clsx'
import {
  connectTelegram,
  disconnectTelegram,
  fetchAlerts,
  saveAlerts,
  testAlert,
  type AlertPrefs,
  type AlertView,
  type Meta,
} from '../lib/api'

/**
 * "Job alerts": the same filters as the website's filter panel, saved; every crawl round, new jobs that match are sent
 * to Telegram. Families, cities and companies: any of them. Skills: all of them. Empty = no filter.
 */
export function AlertsPanel({ open, onClose, meta }: { open: boolean; onClose: () => void; meta: Meta | undefined }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div className="fixed inset-0 z-40 bg-slate-950/40 backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.aside
            role="dialog"
            aria-modal="true"
            aria-label="Job alerts"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 30, stiffness: 300 }}
            className="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col border-l border-slate-200 bg-white shadow-2xl dark:border-white/10 dark:bg-ink-900"
          >
            <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-white/10">
              <span className="flex items-center gap-2 text-sm font-semibold text-slate-900 dark:text-white">
                <Bell size={17} /> Job alerts
              </span>
              <button onClick={onClose} className="grid h-9 w-9 place-items-center rounded-full text-slate-500 hover:bg-slate-100 dark:hover:bg-white/10" aria-label="Close">
                <X size={18} />
              </button>
            </div>
            <AlertsBody meta={meta} />
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  )
}

function AlertsBody({ meta }: { meta: Meta | undefined }) {
  const queryClient = useQueryClient()
  // always fresh: the Telegram link changes outside this page (in the Telegram app), so re-check on open and on return
  const view = useQuery({ queryKey: ['alerts'], queryFn: fetchAlerts, staleTime: 0, refetchOnMount: 'always', refetchOnWindowFocus: true })
  const [draft, setDraft] = useState<AlertPrefs | null>(null)
  const [savedNote, setSavedNote] = useState(false)

  useEffect(() => {
    if (view.data && draft === null) setDraft(view.data.prefs)
  }, [view.data, draft])

  const save = useMutation({
    mutationFn: () => saveAlerts(draft!),
    onSuccess: (saved) => {
      queryClient.setQueryData(['alerts'], saved)
      setDraft(saved.prefs)
      setSavedNote(true)
      setTimeout(() => setSavedNote(false), 2500)
    },
  })

  if (view.isError) return <p className="p-6 text-sm text-rose-500">{(view.error as Error).message}</p>
  if (!view.data || !draft) return <p className="p-6 text-sm text-slate-400">Loading…</p>

  const families = (meta?.families ?? []).filter((f) => f.id !== 'UNCLASSIFIED')
  const set = (patch: Partial<AlertPrefs>) => setDraft({ ...draft, ...patch })
  const yearsBad = draft.minYears != null && draft.maxYears != null && draft.minYears > draft.maxYears

  return (
    <>
      <div className="scrollbar-thin flex-1 space-y-6 overflow-y-auto px-6 py-6">
        <p className="text-sm text-slate-500 dark:text-slate-400">
          After every crawl round, new jobs that match these filters are sent to you on Telegram. They work exactly like
          the website&apos;s filters.{!view.data.saved && ' Suggested from your profile: check and save.'}
        </p>

        <TelegramBox view={view.data} />

        <label className="flex items-center justify-between gap-4 rounded-2xl border border-slate-200 p-4 dark:border-white/10">
          <span>
            <span className="block text-sm font-semibold text-slate-900 dark:text-white">Send me new matching jobs</span>
            <span className="text-xs text-slate-500 dark:text-slate-400">Off: nothing is sent.</span>
          </span>
          <Switch on={draft.enabled} onChange={(enabled) => set({ enabled })} />
        </label>

        <Section title="Job families" hint="any of them; none selected = all families">
          <div className="flex flex-wrap gap-1.5">
            {families.map((f) => {
              const on = draft.families.includes(f.id)
              return (
                <button
                  key={f.id}
                  type="button"
                  onClick={() => set({ families: on ? draft.families.filter((x) => x !== f.id) : [...draft.families, f.id] })}
                  className={clsx(
                    'rounded-full border px-2.5 py-1 text-xs transition',
                    on ? 'border-violet-500 bg-violet-500/10 text-violet-700 dark:text-violet-300' : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-white/10 dark:text-slate-300',
                  )}
                >
                  {f.label}
                </button>
              )
            })}
          </div>
        </Section>

        <Section title="Experience the job asks for" hint="years; empty = any">
          <div className="flex items-center gap-2">
            <NumberBox value={draft.minYears} onChange={(minYears) => set({ minYears })} placeholder="from" invalid={yearsBad} />
            <span className="text-slate-400">to</span>
            <NumberBox value={draft.maxYears} onChange={(maxYears) => set({ maxYears })} placeholder="to" invalid={yearsBad} />
          </div>
          <label className="mt-3 flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
            <input type="checkbox" checked={draft.includeUnstated} onChange={(e) => set({ includeUnstated: e.target.checked })} className="accent-violet-600" />
            Include jobs that don&apos;t state years
          </label>
        </Section>

        <Section title="Skills" hint="the job must ask for ALL of them; empty = any">
          <MultiPick
            values={draft.skills}
            onChange={(skills) => set({ skills })}
            options={(meta?.skills ?? []).map((s) => ({ value: s.name, label: s.name }))}
            placeholder="Add a skill, e.g. Java"
          />
        </Section>

        <Section title="Cities" hint="any of them; empty = anywhere in India">
          <MultiPick
            values={draft.cities}
            onChange={(cities) => set({ cities })}
            options={[{ value: 'Remote', label: 'Remote' }, ...(meta?.cities ?? []).map((c) => ({ value: c.name, label: c.name }))]}
            placeholder="Add a city or Remote"
          />
        </Section>

        <Section title="Companies" hint="any of them; empty = all companies">
          <MultiPick
            values={draft.companies}
            onChange={(companies) => set({ companies })}
            options={(meta?.companies ?? []).map((c) => ({ value: c.slug, label: c.name }))}
            placeholder="Add a company"
          />
        </Section>

        {save.isError && (
          <p role="alert" className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-400">
            {(save.error as Error).message}
          </p>
        )}
      </div>

      <div className="flex items-center gap-3 border-t border-slate-200 p-4 dark:border-white/10">
        {savedNote && (
          <span className="flex items-center gap-1 text-sm text-emerald-600 dark:text-emerald-400">
            <Check size={15} /> Saved
          </span>
        )}
        <button
          onClick={() => save.mutate()}
          disabled={save.isPending || yearsBad}
          className="ml-auto rounded-xl bg-gradient-to-r from-violet-600 to-fuchsia-500 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-violet-500/25 disabled:opacity-50"
        >
          {save.isPending ? 'Saving…' : 'Save alert'}
        </button>
      </div>
    </>
  )
}

/** Connect / status / test / disconnect. After "Connect", the status is re-checked every 3 s until the bot links it. */
function TelegramBox({ view }: { view: AlertView }) {
  const queryClient = useQueryClient()
  const [waiting, setWaiting] = useState(false)
  const t = view.telegram

  useEffect(() => {
    if (!waiting || t.connected) {
      if (t.connected) setWaiting(false)
      return
    }
    const timer = setInterval(() => void queryClient.invalidateQueries({ queryKey: ['alerts'] }), 3000)
    const stop = setTimeout(() => setWaiting(false), 120_000)
    return () => {
      clearInterval(timer)
      clearTimeout(stop)
    }
  }, [waiting, t.connected, queryClient])

  const connect = useMutation({
    mutationFn: connectTelegram,
    onSuccess: ({ url }) => {
      window.open(url, '_blank', 'noopener')
      setWaiting(true)
    },
  })
  const disconnect = useMutation({ mutationFn: disconnectTelegram, onSuccess: (v) => queryClient.setQueryData(['alerts'], v) })
  const test = useMutation({ mutationFn: testAlert })
  const error = (connect.error ?? disconnect.error ?? test.error) as Error | null

  return (
    <div className="rounded-2xl border border-slate-200 p-4 dark:border-white/10">
      <p className="text-xs font-medium tracking-wide text-slate-400 uppercase">Telegram</p>
      {!t.available ? (
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">Telegram alerts aren&apos;t set up on this server yet.</p>
      ) : !t.connected ? (
        <div className="mt-2 space-y-2">
          <p className="text-sm text-slate-600 dark:text-slate-300">
            {waiting ? 'Waiting for you to tap Start in Telegram…' : 'Connect once: Telegram opens, you tap Start, done.'}
          </p>
          <button
            onClick={() => connect.mutate()}
            disabled={connect.isPending}
            className="flex items-center gap-1.5 rounded-xl bg-sky-500 px-4 py-2 text-sm font-semibold text-white hover:bg-sky-400 disabled:opacity-50"
          >
            <Send size={14} /> {waiting ? 'Open Telegram again' : 'Connect Telegram'}
          </button>
        </div>
      ) : (
        <div className="mt-2 space-y-2">
          <p className="flex items-center gap-1.5 text-sm text-slate-700 dark:text-slate-200">
            <Check size={15} className="text-emerald-500" /> Connected to @{t.botUsername}
            {!t.active && <span className="text-amber-600 dark:text-amber-400"> · paused (send /start to the bot)</span>}
          </p>
          <div className="flex gap-2">
            <button
              onClick={() => test.mutate()}
              disabled={test.isPending || !t.active}
              className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 hover:border-sky-400 disabled:opacity-50 dark:border-white/10 dark:text-slate-300"
            >
              {test.isSuccess ? 'Test sent ✓' : 'Send a test message'}
            </button>
            <button
              onClick={() => disconnect.mutate()}
              className="flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-slate-500 hover:bg-slate-100 dark:hover:bg-white/5"
            >
              <Unlink size={12} /> Disconnect
            </button>
          </div>
        </div>
      )}
      {error && <p className="mt-2 text-xs text-rose-500">{error.message}</p>}
    </div>
  )
}

function Section({ title, hint, children }: { title: string; hint: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-slate-600 dark:text-slate-300">
        {title} <span className="font-normal text-slate-400">· {hint}</span>
      </p>
      {children}
    </div>
  )
}

function Switch({ on, onChange }: { on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      onClick={() => onChange(!on)}
      className={clsx('relative h-6 w-11 shrink-0 rounded-full transition', on ? 'bg-violet-600' : 'bg-slate-300 dark:bg-white/20')}
    >
      <span className={clsx('absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition', on ? 'left-[22px]' : 'left-0.5')} />
    </button>
  )
}

function NumberBox({ value, onChange, placeholder, invalid }: { value: number | null; onChange: (v: number | null) => void; placeholder: string; invalid?: boolean }) {
  return (
    <input
      type="number"
      min={0}
      max={50}
      value={value ?? ''}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === '' ? null : Math.max(0, Math.min(50, Math.floor(Number(e.target.value)))))}
      className={clsx(
        'w-24 rounded-xl border bg-white/80 px-3 py-2 text-sm text-slate-900 outline-none focus:ring-4 dark:bg-white/5 dark:text-white',
        invalid ? 'border-rose-400 focus:ring-rose-500/15' : 'border-slate-200 focus:border-violet-400 focus:ring-violet-500/15 dark:border-white/10',
      )}
    />
  )
}

type Option = { value: string; label: string }

/** Chips for the chosen values plus a box that suggests matching options (Enter takes the first suggestion). */
function MultiPick({ values, onChange, options, placeholder, freeText }: {
  values: string[]
  onChange: (values: string[]) => void
  options: Option[]
  placeholder: string
  freeText?: boolean
}) {
  const [text, setText] = useState('')
  const [focused, setFocused] = useState(false)
  const box = useRef<HTMLInputElement>(null)
  const label = (v: string) => options.find((o) => o.value === v)?.label ?? v
  const query = text.trim().toLowerCase()
  const suggestions = options
    .filter((o) => !values.includes(o.value) && (query === '' || o.label.toLowerCase().includes(query)))
    .slice(0, 8)

  const add = (value: string) => {
    if (value && !values.includes(value)) onChange([...values, value])
    setText('')
    box.current?.focus()
  }

  return (
    <div className="relative">
      <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-slate-200 bg-white/80 px-2 py-1.5 focus-within:border-violet-400 dark:border-white/10 dark:bg-white/5">
        {values.map((v) => (
          <span key={v} className="flex items-center gap-1 rounded-full bg-violet-500/10 px-2 py-0.5 text-xs text-violet-700 dark:text-violet-300">
            {label(v)}
            <button type="button" onClick={() => onChange(values.filter((x) => x !== v))} aria-label={`Remove ${label(v)}`}>
              <X size={11} />
            </button>
          </span>
        ))}
        <input
          ref={box}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              if (suggestions[0]) add(suggestions[0].value)
              else if (freeText && text.trim()) add(text.trim())
            } else if (e.key === 'Backspace' && text === '' && values.length) {
              onChange(values.slice(0, -1))
            }
          }}
          placeholder={values.length ? '' : placeholder}
          className="min-w-[8rem] flex-1 bg-transparent px-1 py-1 text-sm text-slate-900 outline-none placeholder:text-slate-400 dark:text-white"
        />
      </div>
      {focused && suggestions.length > 0 && (
        <div className="absolute z-10 mt-1 max-h-56 w-full overflow-y-auto rounded-xl border border-slate-200 bg-white p-1 shadow-xl dark:border-white/10 dark:bg-ink-900">
          {suggestions.map((o) => (
            <button
              key={o.value}
              type="button"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => add(o.value)}
              className="block w-full rounded-lg px-3 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-white/5"
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
