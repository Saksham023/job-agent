import { useQuery } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { SlidersHorizontal, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { CompanyStrip } from './components/CompanyStrip'
import { FilterBar } from './components/FilterBar'
import { FilterPanel } from './components/FilterPanel'
import { Header } from './components/Header'
import { Hero } from './components/Hero'
import { JobDrawer } from './components/JobDrawer'
import { JobList } from './components/JobList'
import { ResultsBar } from './components/ResultsBar'
import { fetchFacets, fetchMeta, fetchProfile, type Filters, type UserProfile } from './lib/api'
import { AlertsPanel } from './components/AlertsPanel'
import { ProfilePanel } from './components/ProfilePanel'
import { ResumeDialog } from './components/ResumeDialog'
import { StatusToast } from './components/JobStatus'
import { activeCount, EMPTY, toggle, useFilters } from './lib/filters'
import type { User } from './lib/auth'

export default function App({ user }: { user: User }) {
  const { filters, update, reset, openJob, setJob } = useFilters()
  const meta = useQuery({ queryKey: ['meta'], queryFn: fetchMeta, staleTime: 5 * 60_000 })
  const facets = useQuery({
    queryKey: ['facets', { ...filters, sort: 'newest' }],
    queryFn: () => fetchFacets(filters),
    placeholderData: (previous) => previous,
  })
  const [total, setTotal] = useState<{ value: number | undefined; loading: boolean }>({ value: undefined, loading: true })
  const [sheet, setSheet] = useState(false)
  // the "All filters" drawer closes with Esc and keeps the page behind it still
  useEffect(() => {
    if (!sheet) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setSheet(false)
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [sheet])
  const profile = useQuery({ queryKey: ['profile'], queryFn: fetchProfile, staleTime: Infinity })
  const [resumeOpen, setResumeOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const [alertsOpen, setAlertsOpen] = useState(false)
  // no profile yet: offer the resume once per visit (closing it hides it until the next visit)
  const offered = useRef(false)
  useEffect(() => {
    if (!offered.current && profile.isSuccess && profile.data === null) {
      offered.current = true
      setResumeOpen(true)
    }
  }, [profile.isSuccess, profile.data])

  /** "Match my resume": the profile's job families and experience range; skills stay as they are on purpose. */
  const matchResume = (p: UserProfile) => {
    update({ families: p.families, minYears: p.jobYearsFrom, maxYears: p.jobYearsTo })
    scrollToResults()
  }

  const scrollToResults = () => document.getElementById('results')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  const onTotal = useCallback((value: number | undefined, loading: boolean) => setTotal({ value, loading }), [])
  const applyPreset = (preset: Partial<Filters>) => {
    update({ ...EMPTY, sort: filters.sort, ...preset })
    scrollToResults()
  }
  const closeJob = useCallback(() => setJob(null), [setJob])

  return (
    <div className="aurora relative min-h-screen overflow-x-clip">
      {profile.isError && (
        <div role="alert" className="fixed inset-x-4 bottom-4 z-30 mx-auto flex max-w-md items-center gap-3 rounded-2xl border border-rose-500/30 bg-white px-4 py-3 text-sm text-rose-600 shadow-xl dark:bg-ink-900 dark:text-rose-400">
          <span className="flex-1">Couldn&apos;t load your profile: {(profile.error as Error).message}</span>
          <button onClick={() => profile.refetch()} className="shrink-0 font-semibold underline-offset-2 hover:underline">
            Try again
          </button>
        </div>
      )}
      <Header user={user} onProfile={() => setProfileOpen(true)} onResume={() => setResumeOpen(true)} onAlerts={() => setAlertsOpen(true)} lastCrawlAt={meta.data?.totals.lastCrawlAt} onHome={() => { reset(); window.scrollTo({ top: 0, behavior: 'smooth' }) }} />

      <Hero
        totals={meta.data?.totals}
        query={filters.q}
        onSearch={(q) => {
          update({ q })
          scrollToResults()
        }}
        onPreset={applyPreset}
      />

      {meta.data && (
        <CompanyStrip
          companies={meta.data.companies}
          selected={filters.companies}
          onToggle={(slug) => {
            update({ companies: toggle(filters.companies, slug) })
            scrollToResults()
          }}
        />
      )}

      <main id="results" className="relative mx-auto mt-10 max-w-7xl scroll-mt-16 px-4 pb-24 sm:px-6">
        <FilterBar
          meta={meta.data}
          facets={facets.data}
          filters={filters}
          update={update}
          profile={profile.data}
          onMatch={matchResume}
          onAddResume={() => setResumeOpen(true)}
          onAllFilters={() => setSheet(true)}
        />
        <section className="mt-5 min-w-0 space-y-5">
          <ResultsBar total={total.value} loading={total.loading} filters={filters} meta={meta.data} update={update} />
          <JobList filters={filters} onOpen={setJob} onTotal={onTotal} onReset={reset} />
        </section>
      </main>

      <footer className="border-t border-slate-200/70 py-8 text-center text-xs text-slate-400 dark:border-white/5">
        Openings are read from each company&apos;s public careers site and refreshed every few hours. Apply on the company&apos;s own page.
      </footer>

      {/* phone: a button that stays in reach for every filter */}
      <button
        onClick={() => setSheet(true)}
        className="fixed bottom-5 left-1/2 z-30 flex -translate-x-1/2 items-center gap-2 rounded-full bg-slate-900 px-5 py-3 text-sm font-semibold text-white shadow-2xl sm:hidden dark:bg-white dark:text-slate-900"
      >
        <SlidersHorizontal size={16} /> Filters{activeCount(filters) > 0 && ` · ${activeCount(filters)}`}
      </button>
      {/* "All filters": every filter in a drawer from the right (the whole screen on a phone) */}
      <AnimatePresence>
        {sheet && (
          <>
            <motion.div className="fixed inset-0 z-40 bg-slate-950/40 backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setSheet(false)} />
            <motion.aside
              role="dialog"
              aria-modal
              aria-label="All filters"
              className="fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col border-l border-slate-200 bg-slate-50 shadow-2xl dark:border-white/10 dark:bg-ink-900"
              initial={{ x: '100%' }}
              animate={{ x: 0 }}
              exit={{ x: '100%' }}
              transition={{ type: 'spring', damping: 30, stiffness: 260 }}
            >
              <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-white/10">
                <span className="font-display text-base font-semibold text-slate-900 dark:text-white">All filters</span>
                <button onClick={() => setSheet(false)} className="grid h-9 w-9 place-items-center rounded-full text-slate-500 hover:bg-slate-100 dark:hover:bg-white/10" aria-label="Close">
                  <X size={18} />
                </button>
              </div>
              <div className="scrollbar-thin flex-1 overflow-y-auto p-4">
                <FilterPanel meta={meta.data} facets={facets.data} filters={filters} update={update} reset={reset} profile={profile.data} onMatch={matchResume} onAddResume={() => setResumeOpen(true)} />
              </div>
              <div className="border-t border-slate-200 p-4 dark:border-white/10">
                <button onClick={() => setSheet(false)} className="w-full rounded-xl bg-gradient-to-r from-violet-600 to-cyan-500 py-3 text-sm font-semibold text-white shadow-lg shadow-violet-600/30">
                  Show {total.value?.toLocaleString('en-IN') ?? ''} roles
                </button>
              </div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>

      <JobDrawer jobId={openJob} onClose={closeJob} onAddResume={() => setResumeOpen(true)} />
      <StatusToast />
      <ResumeDialog open={resumeOpen} onClose={() => setResumeOpen(false)} editedBefore={!!profile.data?.editedAt} />
      <AlertsPanel open={alertsOpen} onClose={() => setAlertsOpen(false)} meta={meta.data} />
      <ProfilePanel
        open={profileOpen}
        onClose={() => setProfileOpen(false)}
        profile={profile.data ?? null}
        families={meta.data?.families ?? []}
        onReplaceResume={() => { setProfileOpen(false); setResumeOpen(true) }}
      />
    </div>
  )
}
