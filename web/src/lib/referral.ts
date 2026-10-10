// The referral message: the user's template (or the default) filled for one job from their profile. No AI involved:
// placeholders are {{name}}, and a part in [square brackets] is dropped when a placeholder inside it has no value.

import type { UserProfile } from './api'

export type ReferralJob = { title: string; company: string; url: string }

/** Used when the profile has no headline yet: the first job family, said the way a person would. */
const FAMILY_HEADLINE: Record<string, string> = {
  SOFTWARE_ENGINEERING: 'software engineer',
  DATA_ML: 'data / ML engineer',
  INFRA_DEVOPS: 'DevOps engineer',
  SECURITY: 'security engineer',
  QA: 'QA engineer',
  ENG_MANAGEMENT: 'engineering manager',
  SALES_ENGINEERING: 'solutions engineer',
  HARDWARE_ENGINEERING: 'hardware engineer',
  PRODUCT: 'product manager',
  DESIGN: 'designer',
  PROGRAM_MANAGEMENT: 'program manager',
  ANALYTICS: 'data analyst',
  QUANT: 'quant researcher',
}

/** "a" or "an" by sound: "an ML engineer", "an iOS developer", "a UX designer", "a backend engineer". */
export function withArticle(phrase: string): string {
  const word = phrase.split(/\s+/)[0] ?? ''
  const acronym = word.length > 1 && /^[A-Z0-9/]+$/.test(word)
  const an = acronym ? /^[AEFHILMNORSX8]/.test(word) : /^[aeiou]/i.test(word) && !/^(uni|use|usu|eu|one)/i.test(word)
  return `${an ? 'an' : 'a'} ${phrase}`
}

/** 1.6 -> "around 2 years", 0.7 -> "around 1 year", 0.3 -> "less than a year". */
export function yearsPhrase(years: number | null): string | null {
  if (years == null) return null
  if (years < 0.5) return 'less than a year'
  const n = Math.round(years)
  return `around ${n} ${n === 1 ? 'year' : 'years'}`
}

/** The main language, then the top 2 skills that are not languages: "Java, Spring Boot and Kafka". */
export function skillsPhrase(profile: Pick<UserProfile, 'mainLanguages' | 'skills'>): string | null {
  const languages = profile.mainLanguages.map((l) => l.toLowerCase())
  const others = profile.skills.filter((s) => !languages.includes(s.toLowerCase()))
  const picked = profile.mainLanguages.length ? [profile.mainLanguages[0], ...others.slice(0, 2)] : others.slice(0, 3)
  if (picked.length === 0) return null
  return picked.length === 1 ? picked[0] : `${picked.slice(0, -1).join(', ')} and ${picked[picked.length - 1]}`
}

/** "around 2 years of experience building ...", or with the skills when the profile has no "what you build". */
export function experiencePhrase(profile: UserProfile): string | null {
  const years = yearsPhrase(profile.years)
  const build = profile.build?.trim()
  if (build) return years ? `${years} of experience ${build}` : `experience ${build}`
  const skills = skillsPhrase(profile)
  if (years && skills) return `${years} of experience in ${skills}`
  if (years) return `${years} of experience`
  if (skills) return `experience in ${skills}`
  return null
}

export function headlinePhrase(profile: UserProfile): string {
  const base = profile.headline?.trim() || FAMILY_HEADLINE[profile.families[0] ?? ''] || 'software professional'
  return withArticle(base)
}

export function fillReferral(template: string, profile: UserProfile, job: ReferralJob): string {
  const values: Record<string, string | null> = {
    company: job.company,
    jobTitle: job.title.trim(),
    jobLink: job.url,
    resumeLink: profile.driveLink,
    headline: headlinePhrase(profile),
    experience: experiencePhrase(profile),
    build: profile.build?.trim() || null,
    years: yearsPhrase(profile.years),
    skills: skillsPhrase(profile),
  }
  const placeholder = /\{\{\s*([A-Za-z]+)\s*\}\}/g
  const has = (name: string) => !!values[name]
  const text = template
    .replace(/\[([^[\]]*)\]/g, (_, part: string) => ([...part.matchAll(placeholder)].every((m) => has(m[1])) ? part : ''))
    .replace(placeholder, (_, name: string) => values[name] ?? '')
  return text.replace(/[ \t]{2,}/g, ' ').replace(/ +([.,!?])/g, '$1').replace(/\n{3,}/g, '\n\n').trim()
}
