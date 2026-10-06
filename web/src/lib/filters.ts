import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { Filters, Sort } from './api'

// The filters live in the URL (?family=DATA_ML&min=0&max=3...), so every search can be bookmarked and shared,
// and the back button undoes a filter change.

const int = (value: string | null): number | null => {
  if (value == null || value === '') return null
  const n = Number.parseInt(value, 10)
  return Number.isFinite(n) ? n : null
}

export const EMPTY: Filters = {
  q: '',
  companies: [],
  families: [],
  minYears: null,
  maxYears: null,
  includeUnstated: true,
  cities: [],
  skills: [],
  postedWithinDays: null,
  sort: 'newest',
}

export function parse(params: URLSearchParams): Filters {
  const sort = params.get('sort')
  return {
    q: params.get('q') ?? '',
    companies: params.getAll('company'),
    families: params.getAll('family'),
    minYears: int(params.get('min')),
    maxYears: int(params.get('max')),
    includeUnstated: params.get('unstated') !== 'no',
    cities: params.getAll('city'),
    skills: params.getAll('skill'),
    postedWithinDays: int(params.get('posted')),
    sort: sort === 'company' || sort === 'experience' ? (sort as Sort) : 'newest',
  }
}

export function serialize(filters: Filters, job?: string | null): URLSearchParams {
  const p = new URLSearchParams()
  if (filters.q) p.set('q', filters.q)
  filters.companies.forEach((c) => p.append('company', c))
  filters.families.forEach((f) => p.append('family', f))
  if (filters.minYears != null) p.set('min', String(filters.minYears))
  if (filters.maxYears != null) p.set('max', String(filters.maxYears))
  if (!filters.includeUnstated) p.set('unstated', 'no')
  filters.cities.forEach((c) => p.append('city', c))
  filters.skills.forEach((s) => p.append('skill', s))
  if (filters.postedWithinDays != null) p.set('posted', String(filters.postedWithinDays))
  if (filters.sort !== 'newest') p.set('sort', filters.sort)
  if (job) p.set('job', job)
  return p
}

export function activeCount(f: Filters): number {
  return (
    f.companies.length +
    f.families.length +
    f.cities.length +
    f.skills.length +
    (f.minYears != null || f.maxYears != null ? 1 : 0) +
    (f.postedWithinDays != null ? 1 : 0) +
    (f.q ? 1 : 0)
  )
}

/** The filters from the URL, a setter that merges changes, and the job opened in the drawer. */
export function useFilters() {
  const [params, setParams] = useSearchParams()
  const filters = useMemo(() => parse(params), [params])
  const openJob = int(params.get('job'))

  const update = useCallback(
    (change: Partial<Filters>) => {
      setParams((current) => serialize({ ...parse(current), ...change }, null), { replace: false })
    },
    [setParams],
  )
  const reset = useCallback(() => setParams(new URLSearchParams()), [setParams])
  const setJob = useCallback(
    (id: number | null) =>
      setParams((current) => serialize(parse(current), id == null ? null : String(id)), { replace: id == null }),
    [setParams],
  )
  return { filters, update, reset, openJob, setJob }
}

export function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}
