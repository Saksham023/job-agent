import { useQuery } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { SlidersHorizontal, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { CompanyStrip } from './components/CompanyStrip'
import { FilterPanel } from './components/FilterPanel'
import { Header } from './components/Header'
import { Hero } from './components/Hero'
import { JobDrawer } from './components/JobDrawer'
import { JobList } from './components/JobList'
import { ResultsBar } from './components/ResultsBar'
import { fetchFacets, fetchMeta, fetchProfile, type Filters, type UserProfile } from './lib/api'
import { ProfilePanel } from './components/ProfilePanel'
import { ResumeDialog } from './components/ResumeDialog'
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
  const profile = useQuery({ queryKey: ['profile'], queryFn: fetchProfile, staleTime: Infinity })
  const [resumeOpen, setResumeOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
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
      <Header user={user} onProfile={() => setProfileOpen(true)} onResume={() => setResumeOpen(true)} lastCrawlAt={meta.data?.totals.lastCrawlAt} onHome={() => { reset(); window.scrollTo({ top: 0, behavior: 'smooth' }) }} />

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

      <main id="results" className="relative mx-auto mt-10 grid max-w-7xl scroll-mt-20 gap-8 px-4 pb-24 sm:px-6 lg:grid-cols-[320px_1fr]">
        <aside className="scrollbar-thin hidden self-start overflow-y-auto pr-1 lg:sticky lg:top-24 lg:block lg:max-h-[calc(100vh-7rem)]">
          <FilterPanel meta={meta.data} facets={facets.data} filters={filters} update={update} reset={reset} profile={profile.data} onMatch={matchResume} onAddResume={() => setResumeOpen(true)} />
        </aside>

        <section className="min-w-0 space-y-5">
          <ResultsBar total={total.value} loading={total.loading} filters={filters} meta={meta.data} update={update} />
          <JobList filters={filters} onOpen={setJob} onTotal={onTotal} onReset={reset} />
        </section>
      </main>

      <footer className="border-t border-slate-200/70 py-8 text-center text-xs text-slate-400 dark:border-white/5">
        Openings are read from each company&apos;s public careers site and refreshed every few hours. Apply on the company&apos;s own page.
      </footer>

      {/* mobile: filters in a bottom sheet */}
      <button
        onClick={() => setSheet(true)}
        className="fixed bottom-5 left-1/2 z-30 flex -translate-x-1/2 items-center gap-2 rounded-full bg-slate-900 px-5 py-3 text-sm font-semibold text-white shadow-2xl lg:hidden dark:bg-white dark:text-slate-900"
      >
        <SlidersHorizontal size={16} /> Filters{activeCount(filters) > 0 && ` · ${activeCount(filters)}`}
      </button>
      <AnimatePresence>
        {sheet && (
          <>
            <motion.div className="fixed inset-0 z-40 bg-slate-950/50 lg:hidden" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setSheet(false)} />
            <motion.div
              className="scrollbar-thin fixed inset-x-0 bottom-0 z-50 max-h-[85vh] overflow-y-auto rounded-t-3xl bg-slate-50 p-4 pb-24 lg:hidden dark:bg-ink-900"
              initial={{ y: '100%' }}
              animate={{ y: 0 }}
              exit={{ y: '100%' }}
              transition={{ type: 'spring', damping: 30, stiffness: 280 }}
            >
              <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-slate-300 dark:bg-white/20" />
              <FilterPanel meta={meta.data} facets={facets.data} filters={filters} update={update} reset={reset} profile={profile.data} onMatch={matchResume} onAddResume={() => setResumeOpen(true)} />
              <button onClick={() => setSheet(false)} className="fixed bottom-5 left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-full bg-gradient-to-r from-violet-600 to-cyan-500 px-6 py-3 text-sm font-semibold text-white shadow-xl">
                <X size={16} /> Show {total.value?.toLocaleString('en-IN') ?? ''} roles
              </button>
            </motion.div>
          </>
        )}
      </AnimatePresence>

      <JobDrawer jobId={openJob} onClose={closeJob} onAddResume={() => setResumeOpen(true)} />
      <ResumeDialog open={resumeOpen} onClose={() => setResumeOpen(false)} editedBefore={!!profile.data?.editedAt} />
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
