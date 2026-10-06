// Types and calls for the Spring Boot public API (/api/v1). The UI never talks to an AI: these are plain reads.

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
  const response = await fetch(path)
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`
    try {
      const body = await response.json()
      message = body.detail ?? message
    } catch {
      /* not JSON */
    }
    throw new Error(message)
  }
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
