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
}

/** Live counts for the filter lists under the current filters. */
export type Facets = {
  families: Record<string, number>
  companies: Record<string, number>
  cities: Record<string, number>
  remote: number
  skills: Count[]
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
  p.set('sort', filters.sort)
  p.set('page', String(page))
  p.set('size', String(size))
  return p.toString()
}

export const fetchJobs = (filters: Filters, page: number) => get<JobPage>(`/api/v1/jobs?${jobsQuery(filters, page)}`)

export const fetchFacets = (filters: Filters) =>
  get<Facets>(`/api/v1/facets?${jobsQuery({ ...filters, sort: 'newest' }, 0, 1)}`)

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
