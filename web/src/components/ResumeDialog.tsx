import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { CheckCircle2, FileUp, Link2, Loader2, Sparkles, X } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import clsx from 'clsx'
import { readResumeLink, uploadResume, type UserProfile } from '../lib/api'

type Mode = 'link' | 'upload'

/**
 * Asks for the resume: a Google Drive link (recommended: we read the resume from it AND put it in referral messages)
 * or a PDF upload. Never required: the × closes it and the job board works without a profile.
 */
export function ResumeDialog({ open, onClose, editedBefore }: { open: boolean; onClose: () => void; editedBefore?: boolean }) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<Mode>('link')
  const [link, setLink] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [done, setDone] = useState<UserProfile | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const read = useMutation({
    mutationFn: () => (mode === 'link' ? readResumeLink(link) : uploadResume(file!)),
    onSuccess: (profile) => {
      queryClient.setQueryData(['profile'], profile)
      setDone(profile)
    },
  })

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !read.isPending && onClose()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose, read.isPending])

  useEffect(() => {
    if (open) {
      setDone(null)
      read.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (mode === 'link' ? link.trim() : file) read.mutate()
  }

  return (
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-50 grid place-items-center p-4" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
          <div className="absolute inset-0 bg-slate-950/50 backdrop-blur-sm" onClick={() => !read.isPending && onClose()} />
          <motion.div
            role="dialog"
            aria-modal="true"
            aria-labelledby="resume-title"
            initial={{ opacity: 0, y: 16, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 16, scale: 0.98 }}
            className="relative w-full max-w-lg rounded-3xl border border-slate-200 bg-white p-6 shadow-2xl sm:p-7 dark:border-white/10 dark:bg-ink-900"
          >
            <button
              onClick={onClose}
              disabled={read.isPending}
              className="absolute top-4 right-4 grid h-9 w-9 place-items-center rounded-full text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40 dark:hover:bg-white/10 dark:hover:text-white"
              aria-label="Close, add it later"
            >
              <X size={18} />
            </button>

            {done ? (
              <Summary profile={done} onClose={onClose} />
            ) : read.isPending ? (
              <div className="flex flex-col items-center py-10 text-center">
                <Loader2 className="animate-spin text-violet-500" size={32} />
                <p className="mt-4 font-display text-lg font-semibold text-slate-900 dark:text-white">Reading your resume…</p>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">This takes about 15 seconds.</p>
              </div>
            ) : (
              <>
                <span className="grid h-11 w-11 place-items-center rounded-2xl bg-gradient-to-br from-violet-500 to-cyan-500 text-white shadow-lg shadow-violet-500/30">
                  <Sparkles size={20} />
                </span>
                <h2 id="resume-title" className="mt-4 font-display text-xl font-semibold text-slate-900 dark:text-white">
                  Add your resume
                </h2>
                <p className="mt-1.5 text-sm text-slate-500 dark:text-slate-400">
                  We read your experience and skills once, so you can filter jobs that fit you and get a ready referral message.
                  Only the facts are kept, never the file.
                </p>
                {editedBefore && (
                  <p className="mt-3 rounded-xl bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
                    Reading a new resume replaces the details you edited by hand.
                  </p>
                )}

                <div className="mt-5 grid grid-cols-2 gap-2 text-sm font-medium">
                  {(['link', 'upload'] as const).map((m) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => setMode(m)}
                      className={clsx(
                        'relative flex items-center justify-center gap-2 rounded-xl border py-2.5 transition',
                        mode === m
                          ? 'border-violet-500 bg-violet-500/10 text-violet-700 dark:text-violet-300'
                          : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-white/10 dark:text-slate-300',
                      )}
                    >
                      {m === 'link' ? <Link2 size={15} /> : <FileUp size={15} />}
                      {m === 'link' ? 'Drive link' : 'Upload a PDF'}
                      {m === 'link' && (
                        <span className="absolute -top-2 right-2 rounded-full bg-emerald-500 px-1.5 py-0.5 text-[10px] leading-none font-semibold text-white shadow-sm">
                          Recommended
                        </span>
                      )}
                    </button>
                  ))}
                </div>

                <form onSubmit={submit} className="mt-4 space-y-3">
                  {mode === 'link' ? (
                    <>
                      <input
                        type="url"
                        value={link}
                        onChange={(e) => setLink(e.target.value)}
                        placeholder="https://drive.google.com/file/d/…/view"
                        className="w-full rounded-xl border border-slate-200 bg-white/80 px-3.5 py-2.5 text-sm text-slate-900 outline-none placeholder:text-slate-400 focus:border-violet-400 focus:ring-4 focus:ring-violet-500/15 dark:border-white/10 dark:bg-white/5 dark:text-white"
                      />
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        Share the file as <b>Anyone with the link can view</b>. The link also goes into your referral messages, so
                        people can open your resume in one click.
                      </p>
                    </>
                  ) : (
                    <>
                      <input ref={fileInput} type="file" accept="application/pdf,.pdf" className="hidden" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
                      <button
                        type="button"
                        onClick={() => fileInput.current?.click()}
                        className="flex w-full flex-col items-center gap-1 rounded-xl border-2 border-dashed border-slate-200 py-6 text-sm text-slate-500 transition hover:border-violet-400 dark:border-white/10 dark:text-slate-400"
                      >
                        <FileUp size={20} />
                        {file ? <span className="font-medium text-slate-800 dark:text-slate-100">{file.name}</span> : 'Choose a PDF (up to 5 MB)'}
                      </button>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        Without a link, referral messages can't include your resume; you can add a link later on your profile.
                      </p>
                    </>
                  )}

                  {read.isError && (
                    <p role="alert" className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-400">
                      {(read.error as Error).message}
                    </p>
                  )}

                  <div className="flex items-center justify-between gap-3 pt-1">
                    <button type="button" onClick={onClose} className="text-sm text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-white">
                      Later
                    </button>
                    <button
                      type="submit"
                      disabled={mode === 'link' ? !link.trim() : !file}
                      className="rounded-xl bg-gradient-to-r from-violet-600 to-fuchsia-500 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-violet-500/25 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      Read my resume
                    </button>
                  </div>
                </form>
              </>
            )}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

function Summary({ profile, onClose }: { profile: UserProfile; onClose: () => void }) {
  return (
    <div className="py-2">
      <CheckCircle2 className="text-emerald-500" size={30} />
      <h2 className="mt-3 font-display text-xl font-semibold text-slate-900 dark:text-white">Got it</h2>
      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Here is what we read. You can change any of it on your profile.</p>
      <dl className="mt-4 space-y-2 text-sm">
        <Row label="Headline" value={profile.headline ?? '—'} />
        <Row label="What you build" value={profile.build ?? '—'} />
        <Row label="Experience" value={profile.years != null ? `${profile.years} years` : 'not found'} />
        <Row label="Main languages" value={profile.mainLanguages.join(', ') || '—'} />
        <Row label="Top skills" value={profile.skills.slice(0, 6).join(', ') || '—'} />
        <Row label="Looking for" value={profile.rolesWanted ?? '—'} />
      </dl>
      <button
        onClick={onClose}
        className="mt-6 w-full rounded-xl bg-slate-900 py-2.5 text-sm font-semibold text-white transition hover:bg-slate-800 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-100"
      >
        Start exploring
      </button>
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-3">
      <dt className="w-32 shrink-0 text-slate-400">{label}</dt>
      <dd className="text-slate-800 dark:text-slate-200">{value}</dd>
    </div>
  )
}
