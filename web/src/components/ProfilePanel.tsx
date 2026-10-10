import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { FileText, Link2, MessageSquareText, Pencil, RefreshCw, RotateCcw, UserRound, X } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import clsx from 'clsx'
import { fetchReferralTemplate, resetReferralTemplate, saveProfile, saveReferralTemplate, type Family, type UserProfile } from '../lib/api'
import { fillReferral } from '../lib/referral'

type Draft = { headline: string; build: string; years: string; languages: string; skills: string; roles: string; families: string[]; link: string }

const toDraft = (p: UserProfile | null): Draft => ({
  headline: p?.headline ?? '',
  build: p?.build ?? '',
  years: p?.years != null ? String(p.years) : '',
  languages: p?.mainLanguages.join(', ') ?? '',
  skills: p?.skills.join(', ') ?? '',
  roles: p?.rolesWanted ?? '',
  families: p?.families ?? [],
  link: p?.driveLink ?? '',
})

const list = (text: string) => text.split(',').map((s) => s.trim()).filter(Boolean)

/** "My profile": what we read from the resume, editable by the user. Their edits feed the filters and referral messages. */
export function ProfilePanel({
  open,
  onClose,
  profile,
  families,
  onReplaceResume,
}: {
  open: boolean
  onClose: () => void
  profile: UserProfile | null
  families: Family[]
  onReplaceResume: () => void
}) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Draft>(toDraft(profile))

  useEffect(() => {
    if (open) {
      setDraft(toDraft(profile))
      setEditing(!profile)
    }
  }, [open, profile])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const save = useMutation({
    mutationFn: () =>
      saveProfile({
        headline: draft.headline.trim() || null,
        build: draft.build.trim() || null,
        years: draft.years.trim() === '' ? null : Number(draft.years),
        mainLanguages: list(draft.languages),
        skills: list(draft.skills),
        rolesWanted: draft.roles.trim() || null,
        families: draft.families,
        driveLink: draft.link.trim() || null,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData(['profile'], saved)
      setEditing(false)
    },
  })

  const label = (id: string) => families.find((f) => f.id === id)?.label ?? id
  const yearsInvalid = draft.years.trim() !== '' && (Number.isNaN(Number(draft.years)) || Number(draft.years) < 0 || Number(draft.years) > 50)

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div className="fixed inset-0 z-40 bg-slate-950/40 backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.aside
            role="dialog"
            aria-modal="true"
            aria-label="My profile"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 30, stiffness: 300 }}
            className="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col border-l border-slate-200 bg-white shadow-2xl dark:border-white/10 dark:bg-ink-900"
          >
            <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-white/10">
              <span className="flex items-center gap-2 text-sm font-semibold text-slate-900 dark:text-white">
                <UserRound size={17} /> My profile
              </span>
              <button onClick={onClose} className="grid h-9 w-9 place-items-center rounded-full text-slate-500 hover:bg-slate-100 dark:hover:bg-white/10" aria-label="Close">
                <X size={18} />
              </button>
            </div>

            <div className="scrollbar-thin flex-1 space-y-6 overflow-y-auto px-6 py-6">
              {!profile && !editing && <p className="text-sm text-slate-500">No profile yet.</p>}

              {profile && !editing && (
                <>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    {profile.editedAt
                      ? 'Edited by you.'
                      : profile.source === 'drive'
                        ? 'Read from your Google Drive resume.'
                        : profile.source === 'upload'
                          ? 'Read from your uploaded resume.'
                          : 'Entered by you.'}{' '}
                    Used for "Match my resume" and your referral messages.
                  </p>
                  <Field label="Headline">{profile.headline ?? 'Not set'}</Field>
                  <Field label="What you build">{profile.build ?? 'Not set'}</Field>
                  <Field label="Experience">
                    {profile.years != null ? `${profile.years} years` : 'Not set'}
                    {profile.jobYearsFrom != null && (
                      <span className="ml-2 text-xs text-slate-400">(matches jobs asking {profile.jobYearsFrom}–{profile.jobYearsTo} years)</span>
                    )}
                  </Field>
                  <Field label="Main languages">
                    <Chips items={profile.mainLanguages} />
                  </Field>
                  <Field label="Skills">
                    <Chips items={profile.skills} />
                  </Field>
                  <Field label="Looking for">{profile.rolesWanted ?? 'Not set'}</Field>
                  <Field label="Job families">
                    <Chips items={profile.families.map(label)} />
                  </Field>
                  <Field label="Resume link">
                    {profile.driveLink ? (
                      <a href={profile.driveLink} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 break-all text-violet-600 hover:underline dark:text-violet-400">
                        <Link2 size={14} /> {profile.driveLink}
                      </a>
                    ) : (
                      <span className="text-slate-400">None: add one so referral messages can include your resume</span>
                    )}
                  </Field>
                  <ReferralSection profile={profile} />
                </>
              )}

              {editing && (
                <form
                  id="profile-form"
                  className="space-y-5"
                  onSubmit={(e) => {
                    e.preventDefault()
                    if (!yearsInvalid) save.mutate()
                  }}
                >
                  <Input label="Headline" hint="your broad role, e.g. backend engineer" value={draft.headline} onChange={(headline) => setDraft({ ...draft, headline })} />
                  <Input
                    label="What you build"
                    hint="starts with a verb, e.g. building high-throughput microservices in Java and Spring Boot"
                    value={draft.build}
                    onChange={(build) => setDraft({ ...draft, build })}
                  />
                  <Input label="Years of experience" hint="e.g. 1.6" value={draft.years} onChange={(years) => setDraft({ ...draft, years })} invalid={yearsInvalid} />
                  <Input label="Main languages" hint="comma separated, most used first, e.g. Java, Python" value={draft.languages} onChange={(languages) => setDraft({ ...draft, languages })} />
                  <Input label="Skills" hint="comma separated, most important first" value={draft.skills} onChange={(skills) => setDraft({ ...draft, skills })} textarea />
                  <Input label="Looking for" hint="e.g. Backend engineering roles" value={draft.roles} onChange={(roles) => setDraft({ ...draft, roles })} />
                  <div>
                    <span className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">Job families (up to 4)</span>
                    <div className="flex flex-wrap gap-1.5">
                      {families
                        .filter((f) => f.id !== 'UNCLASSIFIED')
                        .map((f) => {
                          const on = draft.families.includes(f.id)
                          return (
                            <button
                              key={f.id}
                              type="button"
                              onClick={() =>
                                setDraft({ ...draft, families: on ? draft.families.filter((x) => x !== f.id) : [...draft.families, f.id].slice(0, 4) })
                              }
                              className={clsx(
                                'rounded-full border px-2.5 py-1 text-xs transition',
                                on
                                  ? 'border-violet-500 bg-violet-500/10 text-violet-700 dark:text-violet-300'
                                  : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-white/10 dark:text-slate-300',
                              )}
                            >
                              {f.label}
                            </button>
                          )
                        })}
                    </div>
                  </div>
                  <Input label="Resume link (Google Drive)" hint="https://drive.google.com/file/d/…" value={draft.link} onChange={(link) => setDraft({ ...draft, link })} />
                  {save.isError && (
                    <p role="alert" className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-400">
                      {(save.error as Error).message}
                    </p>
                  )}
                </form>
              )}
            </div>

            <div className="flex items-center gap-2 border-t border-slate-200 p-4 dark:border-white/10">
              {editing ? (
                <>
                  {profile && (
                    <button onClick={() => { setDraft(toDraft(profile)); setEditing(false) }} className="rounded-xl px-4 py-2.5 text-sm text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">
                      Cancel
                    </button>
                  )}
                  <button
                    type="submit"
                    form="profile-form"
                    disabled={save.isPending || yearsInvalid}
                    className="ml-auto rounded-xl bg-gradient-to-r from-violet-600 to-fuchsia-500 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-violet-500/25 disabled:opacity-50"
                  >
                    {save.isPending ? 'Saving…' : 'Save'}
                  </button>
                </>
              ) : (
                <>
                  <button onClick={onReplaceResume} className="flex items-center gap-1.5 rounded-xl px-4 py-2.5 text-sm text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">
                    {profile ? <RefreshCw size={15} /> : <FileText size={15} />} {profile ? 'Replace resume' : 'Add resume'}
                  </button>
                  <button
                    onClick={() => setEditing(true)}
                    className="ml-auto flex items-center gap-1.5 rounded-xl bg-slate-900 px-5 py-2.5 text-sm font-semibold text-white dark:bg-white dark:text-slate-900"
                  >
                    <Pencil size={14} /> Edit
                  </button>
                </>
              )}
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  )
}

/** Example job for the preview in the profile (the drawer fills the real job). */
const EXAMPLE_JOB = { title: 'Software Engineer', company: 'Acme', url: 'https://careers.acme.com/jobs/123' }

/** The referral message: preview, and the user's own wording (placeholders + optional [parts]), saved per account. */
function ReferralSection({ profile }: { profile: UserProfile }) {
  const queryClient = useQueryClient()
  const template = useQuery({ queryKey: ['referral-template'], queryFn: fetchReferralTemplate, staleTime: Infinity })
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const box = useRef<HTMLTextAreaElement>(null)

  const done = (saved: Awaited<ReturnType<typeof fetchReferralTemplate>>) => {
    queryClient.setQueryData(['referral-template'], saved)
    setEditing(false)
  }
  const save = useMutation({ mutationFn: () => saveReferralTemplate(text), onSuccess: done })
  const reset = useMutation({ mutationFn: resetReferralTemplate, onSuccess: done })

  if (!template.data) return null
  const insert = (name: string) => {
    const el = box.current
    const at = el?.selectionStart ?? text.length
    const next = `${text.slice(0, at)}{{${name}}}${text.slice(el?.selectionEnd ?? at)}`
    setText(next)
    requestAnimationFrame(() => {
      el?.focus()
      el?.setSelectionRange(at + name.length + 4, at + name.length + 4)
    })
  }

  return (
    <div className="rounded-2xl border border-slate-200 p-4 dark:border-white/10">
      <div className="flex items-center justify-between gap-3">
        <p className="flex items-center gap-1.5 text-xs font-medium tracking-wide text-slate-400 uppercase">
          <MessageSquareText size={14} className="text-violet-500" /> Referral message
        </p>
        {!editing && (
          <button
            onClick={() => { setText(template.data.text); save.reset(); setEditing(true) }}
            className="flex items-center gap-1 text-xs font-semibold text-violet-600 hover:underline dark:text-violet-400"
          >
            <Pencil size={12} /> Change wording
          </button>
        )}
      </div>

      {!editing ? (
        <>
          <p className="mt-3 text-sm leading-relaxed whitespace-pre-line [overflow-wrap:anywhere] text-slate-700 dark:text-slate-200">
            {fillReferral(template.data.text, profile, EXAMPLE_JOB)}
          </p>
          <p className="mt-2 text-xs text-slate-400">
            Preview with an example job. {template.data.custom ? 'Your own wording.' : 'The default wording.'} Each job fills in its own title, company and link.
          </p>
        </>
      ) : (
        <div className="mt-3 space-y-3">
          <textarea
            ref={box}
            rows={6}
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="w-full rounded-xl border border-slate-200 bg-white/80 px-3.5 py-2.5 font-mono text-xs leading-relaxed text-slate-900 outline-none focus:border-violet-400 focus:ring-4 focus:ring-violet-500/15 dark:border-white/10 dark:bg-white/5 dark:text-white"
          />
          <div className="flex flex-wrap gap-1.5">
            {template.data.placeholders.map((name) => (
              <button
                key={name}
                type="button"
                onClick={() => insert(name)}
                className="rounded-full border border-slate-200 px-2 py-0.5 font-mono text-[11px] text-slate-600 hover:border-violet-400 hover:text-violet-600 dark:border-white/10 dark:text-slate-300"
              >
                {`{{${name}}}`}
              </button>
            ))}
          </div>
          <p className="text-xs text-slate-400">
            Click a placeholder to insert it. Put a part in [square brackets] to leave it out when its value is missing, e.g.{' '}
            <code>[ Resume: {'{{resumeLink}}'}]</code>.
          </p>
          {text.trim() && (
            <p className="rounded-xl bg-slate-50 px-3 py-2 text-xs leading-relaxed text-slate-600 dark:bg-white/[0.04] dark:text-slate-300">
              {fillReferral(text, profile, EXAMPLE_JOB)}
            </p>
          )}
          {(save.isError || reset.isError) && (
            <p role="alert" className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-400">
              {((save.error ?? reset.error) as Error).message}
            </p>
          )}
          <div className="flex items-center gap-2">
            {template.data.custom && (
              <button
                type="button"
                onClick={() => reset.mutate()}
                disabled={reset.isPending}
                className="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs text-slate-500 hover:bg-slate-100 dark:hover:bg-white/5"
              >
                <RotateCcw size={12} /> Reset to default
              </button>
            )}
            <button type="button" onClick={() => setEditing(false)} className="ml-auto rounded-lg px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/5">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => save.mutate()}
              disabled={save.isPending || !text.trim()}
              className="rounded-lg bg-violet-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-violet-500 disabled:opacity-50"
            >
              {save.isPending ? 'Saving…' : 'Save wording'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium tracking-wide text-slate-400 uppercase">{label}</p>
      <div className="text-sm text-slate-800 dark:text-slate-200">{children}</div>
    </div>
  )
}

function Chips({ items }: { items: string[] }) {
  if (items.length === 0) return <span className="text-slate-400">Not set</span>
  return (
    <div className="flex flex-wrap gap-1.5">
      {items.map((item) => (
        <span key={item} className="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-700 dark:bg-white/10 dark:text-slate-200">
          {item}
        </span>
      ))}
    </div>
  )
}

function Input({
  label,
  hint,
  value,
  onChange,
  textarea,
  invalid,
}: {
  label: string
  hint: string
  value: string
  onChange: (v: string) => void
  textarea?: boolean
  invalid?: boolean
}) {
  const cls = clsx(
    'w-full rounded-xl border bg-white/80 px-3.5 py-2.5 text-sm text-slate-900 outline-none placeholder:text-slate-400 focus:ring-4 dark:bg-white/5 dark:text-white',
    invalid ? 'border-rose-400 focus:ring-rose-500/15' : 'border-slate-200 focus:border-violet-400 focus:ring-violet-500/15 dark:border-white/10',
  )
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">{label}</span>
      {textarea ? (
        <textarea rows={3} value={value} onChange={(e) => onChange(e.target.value)} placeholder={hint} className={cls} />
      ) : (
        <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={hint} className={cls} />
      )}
    </label>
  )
}
