// Links into LinkedIn's own people search, so the user sees who they know at a company. Nothing is fetched or stored by
// us: the search runs in the user's browser, under their own LinkedIn login (LinkedIn offers no API for connections).

export type Degree = 'F' | 'S' // 1st-degree connections, or 2nd-degree (people your connections know)

/**
 * With LinkedIn's numeric company id (or several, "1753,12345", for a company hiring under several pages): people
 * CURRENTLY at the company. Without it: the company name as a keyword,
 * which also finds people who used to work there.
 */
export function connectionsUrl(company: string, linkedinCompanyId: string | null, degree: Degree = 'F'): string {
  const network = encodeURIComponent(`["${degree}"]`)
  const base = 'https://www.linkedin.com/search/results/people/'
  if (linkedinCompanyId && /^\d+(,\d+)*$/.test(linkedinCompanyId)) {
    const ids = linkedinCompanyId.split(',').map((id) => `"${id}"`).join(',')
    return `${base}?currentCompany=${encodeURIComponent(`[${ids}]`)}&network=${network}`
  }
  return `${base}?keywords=${encodeURIComponent(company)}&network=${network}`
}
