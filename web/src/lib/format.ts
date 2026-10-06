export function years(minYears: number | null, maxYears: number | null, stated: boolean): string {
  if (!stated || minYears == null) return 'Experience not stated'
  if (maxYears == null) return `${minYears}+ yrs`
  if (minYears === maxYears) return `${minYears} yrs`
  return `${minYears}–${maxYears} yrs`
}

export function ago(iso: string | null): string {
  if (!iso) return ''
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))}m ago`
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`
  const days = Math.round(seconds / 86400)
  if (days < 30) return `${days}d ago`
  const months = Math.round(days / 30)
  return months < 12 ? `${months}mo ago` : `${Math.round(months / 12)}y ago`
}

export const isFresh = (iso: string | null) => !!iso && Date.now() - new Date(iso).getTime() < 3 * 86400_000

export function compact(n: number): string {
  return n >= 10_000 ? `${(n / 1000).toFixed(0)}k` : n.toLocaleString('en-IN')
}

const PALETTES = [
  'from-violet-500 to-fuchsia-500',
  'from-cyan-500 to-blue-500',
  'from-emerald-500 to-teal-500',
  'from-amber-500 to-orange-500',
  'from-rose-500 to-pink-500',
  'from-indigo-500 to-violet-500',
  'from-sky-500 to-cyan-400',
  'from-lime-500 to-emerald-500',
]

/** A stable gradient per company, so each one keeps its color everywhere. */
export function companyGradient(slug: string): string {
  let hash = 0
  for (const ch of slug) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return PALETTES[hash % PALETTES.length]
}

export function initials(name: string): string {
  const words = name.replace(/[^A-Za-z0-9 ]/g, ' ').split(/\s+/).filter(Boolean)
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

export const FAMILY_SHORT: Record<string, string> = {
  SOFTWARE_ENGINEERING: 'Software',
  DATA_ML: 'Data & ML',
  INFRA_DEVOPS: 'Infra & DevOps',
  SECURITY: 'Security',
  QA: 'QA',
  ENG_MANAGEMENT: 'Eng. Management',
  HARDWARE_ENGINEERING: 'Hardware',
  SALES_ENGINEERING: 'Solutions',
  PRODUCT: 'Product',
  DESIGN: 'Design',
  PROGRAM_MANAGEMENT: 'Program Mgmt',
  ANALYTICS: 'Analytics',
  SALES: 'Sales',
  MARKETING: 'Marketing',
  FINANCE: 'Finance',
  RISK_COMPLIANCE: 'Risk',
  HR: 'HR',
  LEGAL: 'Legal',
  SUPPORT: 'Support',
  OPERATIONS: 'Operations',
  UNCLASSIFIED: 'Other',
}
