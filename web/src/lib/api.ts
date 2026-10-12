// Types and calls for the Spring Boot API (/api/v1). Every call carries the signed-in user's access token (authFetch).
// The UI never talks to an AI: these are plain reads.

import { authFetch, errorOf } from './auth'

export type Count = { name: string; jobs: number }

export type Company = { slug: string; name: string; platform: string; openJobs: number; lastCrawlAt: string | null }

export type Family = { id: string; label: string; group: 'TECH' | 'TECH_ADJACENT' | 'BUSINESS'; jobs: number }

export type Totals = {
  openJobs: number
  companies: number
  platforms: number
  postedThisWeek: number
  remoteJobs: number
  lastCrawlAt: string | null
}

export type Meta = { totals: Totals; companies: Company[]; families: Family[]; cities: Count[]; skills: Count[] }

/** What the signed-in user marked a job as (at most one). Hiding a job is separate: a job can be Applied AND hidden. */
export type JobStatus = 'SAVED' | 'APPLIED' | 'REFERRAL_ASKED'

export const JOB_STATUSES: JobStatus[] = ['SAVED', 'APPLIED', 'REFERRAL_ASKED']

/** What "Show only" can pick: a status, or HIDDEN for the jobs the user hid (whatever their status). */
export type StatusFilter = JobStatus | 'HIDDEN'

export const SHOW_ONLY: StatusFilter[] = [...JOB_STATUSES, 'HIDDEN']

/** The statuses one can also hide for now (hidden jobs are always left out). */
export const HIDEABLE: JobStatus[] = ['SAVED', 'APPLIED', 'REFERRAL_ASKED']

export type JobCard = {
  id: number
  title: string
  companySlug: string
  company: string
  cities: string[]
  remote: boolean
  minYears: number | null
  maxYears: number | null
  yearsStated: boolean
  family: string | null
  specialization: string | null
  skills: string[]
  postedAt: string | null
  url: string
  /** the signed-in user's status for this job, null = none */
  status: JobStatus | null
  /** the signed-in user hid this job (left out of the normal search) */
  hidden: boolean
}

export type JobPage = { total: number; page: number; size: number; hasMore: boolean; jobs: JobCard[] }

export type JobDetail = JobCard & {
  department: string | null
  locations: string[]
  employmentType: string | null
  yearsEvidence: string | null
  secondaryFamilies: string[]
  requiredSkills: string[]
  preferredSkills: string[]
  primaryLanguages: string[]
  description: string | null
  /** LinkedIn's numeric company id when we know it (precise "who do I know there" search), else null */
  linkedinCompanyId: string | null
  /** other countries the same posting is listed in (its cities there are not shown) */
  otherCountries: number
}

/** Live counts for the filter lists under the current filters. */
export type Facets = {
  families: Record<string, number>
  companies: Record<string, number>
  cities: Record<string, number>
  remote: number
  skills: Count[]
  /** how many jobs the user marked with each status, under the other filters */
  statuses: Record<string, number>
}

export type Sort = 'newest' | 'company' | 'experience'

export type Filters = {
  q: string
  companies: string[]
  families: string[]
  minYears: number | null
  maxYears: number | null
  includeUnstated: boolean
  cities: string[]
  skills: string[]
  postedWithinDays: number | null
  /** only jobs I marked with one of these (HIDDEN = the jobs I hid); empty = all jobs except the hidden ones */
  statuses: StatusFilter[]
  /** statuses also hidden for now (e.g. APPLIED), on top of the hidden jobs; ignored while `statuses` is set */
  hidden: JobStatus[]
  sort: Sort
}

async function get<T>(path: string): Promise<T> {
  const response = await authFetch(path)
  if (!response.ok) throw new Error(await errorOf(response))
  return response.json() as Promise<T>
}

export const fetchMeta = () => get<Meta>('/api/v1/meta')

export const fetchJob = (id: number) => get<JobDetail>(`/api/v1/jobs/${id}`)

export function jobsQuery(filters: Filters, page: number, size = 24): string {
  const p = new URLSearchParams()
  if (filters.q) p.set('q', filters.q)
  filters.companies.forEach((c) => p.append('company', c))
  filters.families.forEach((f) => p.append('family', f))
  if (filters.minYears != null) p.set('minYears', String(filters.minYears))
  if (filters.maxYears != null) p.set('maxYears', String(filters.maxYears))
  if (!filters.includeUnstated) p.set('includeUnstated', 'false')
  filters.cities.forEach((c) => p.append('city', c))
  filters.skills.forEach((s) => p.append('skill', s))
  if (filters.postedWithinDays != null) p.set('postedWithinDays', String(filters.postedWithinDays))
  filters.statuses.forEach((s) => p.append('status', s))
  filters.hidden.forEach((s) => p.append('hide', s))
  p.set('sort', filters.sort)
  p.set('page', String(page))
  p.set('size', String(size))
  return p.toString()
}

export const fetchJobs = (filters: Filters, page: number) => get<JobPage>(`/api/v1/jobs?${jobsQuery(filters, page)}`)

export const fetchFacets = (filters: Filters) =>
  get<Facets>(`/api/v1/facets?${jobsQuery({ ...filters, sort: 'newest' }, 0, 1)}`)

/** Sets (or, with null, clears) the signed-in user's status for one job. */
export async function setJobStatus(jobId: number, status: JobStatus | null): Promise<void> {
  const response = await authFetch(
    `/api/v1/me/jobs/${jobId}/status`,
    status == null
      ? { method: 'DELETE' }
      : { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status }) },
  )
  if (!response.ok) throw new Error(await errorOf(response))
}

/** Hides one job from the signed-in user's search, or shows it again; its status stays. */
export async function setJobHidden(jobId: number, hidden: boolean): Promise<void> {
  const response = await authFetch(`/api/v1/me/jobs/${jobId}/hidden`, { method: hidden ? 'PUT' : 'DELETE' })
  if (!response.ok) throw new Error(await errorOf(response))
}

// ---------------------------------------------------------------- the signed-in user's profile (read from their resume)

export type UserProfile = {
  /** the broad role, e.g. "backend engineer" (used in the referral message) */
  headline: string | null
  /** what the person builds, e.g. "building high-throughput microservices in Java" (follows "years of experience") */
  build: string | null
  years: number | null
  mainLanguages: string[]
  skills: string[]
  rolesWanted: string | null
  families: string[]
  driveLink: string | null
  source: 'drive' | 'upload' | 'manual'
  readAt: string | null
  editedAt: string | null
  /** the experience range "Match my resume" filters on */
  jobYearsFrom: number | null
  jobYearsTo: number | null
}

export type ProfileEdit = Pick<UserProfile, 'headline' | 'build' | 'years' | 'mainLanguages' | 'skills' | 'rolesWanted' | 'families' | 'driveLink'>

async function send<T>(path: string, init: RequestInit): Promise<T> {
  const response = await authFetch(path, init)
  if (!response.ok) throw new Error(await errorOf(response))
  return response.json() as Promise<T>
}

/** The profile, or null when the user has none yet (204). */
export async function fetchProfile(): Promise<UserProfile | null> {
  const response = await authFetch('/api/v1/me/profile')
  if (response.status === 204) return null
  if (!response.ok) throw new Error(await errorOf(response))
  return response.json() as Promise<UserProfile>
}

export const saveProfile = (edit: ProfileEdit) =>
  send<UserProfile>('/api/v1/me/profile', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(edit) })

export const readResumeLink = (link: string) =>
  send<UserProfile>('/api/v1/me/resume/link', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ link }) })

export function uploadResume(file: File): Promise<UserProfile> {
  const form = new FormData()
  form.append('file', file)
  return send<UserProfile>('/api/v1/me/resume/upload', { method: 'POST', body: form })
}

// ---------------------------------------------------------------- the referral message template (filled per job in lib/referral.ts)

export type ReferralTemplate = { text: string; custom: boolean; defaultText: string; placeholders: string[] }

export const fetchReferralTemplate = () => get<ReferralTemplate>('/api/v1/me/referral-template')

export const saveReferralTemplate = (text: string) =>
  send<ReferralTemplate>('/api/v1/me/referral-template', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) })

export const resetReferralTemplate = () => send<ReferralTemplate>('/api/v1/me/referral-template', { method: 'DELETE' })

// ---------------------------------------------------------------- job alerts (Telegram, after every crawl round)

/** Empty list or null = no filter on that field, as on the website. Families, cities, companies: any of; skills: all of. */
export type AlertPrefs = {
  enabled: boolean
  families: string[]
  minYears: number | null
  maxYears: number | null
  includeUnstated: boolean
  skills: string[]
  cities: string[]
  companies: string[]
}

export type AlertView = {
  prefs: AlertPrefs
  /** false = nothing saved yet: the filters are suggestions from the profile */
  saved: boolean
  telegram: { available: boolean; connected: boolean; active: boolean; botUsername: string | null }
}

export const fetchAlerts = () => get<AlertView>('/api/v1/me/alerts')

export const saveAlerts = (prefs: AlertPrefs) =>
  send<AlertView>('/api/v1/me/alerts', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(prefs) })

export const connectTelegram = () => send<{ url: string }>('/api/v1/me/alerts/telegram', { method: 'POST' })

export const disconnectTelegram = () => send<AlertView>('/api/v1/me/alerts/telegram', { method: 'DELETE' })

export async function testAlert(): Promise<void> {
  const response = await authFetch('/api/v1/me/alerts/test', { method: 'POST' })
  if (!response.ok) throw new Error(await errorOf(response))
}
