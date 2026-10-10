// Signing in, and every API call made with the access token.
//
// The access token (a 15-minute JWT) lives only in this module's memory: never in localStorage, so a script injected
// into the page cannot steal a long-lived credential. The refresh token is an HttpOnly cookie the browser sends to
// /api/v1/auth by itself; scripts cannot read it. On page load and shortly before the access token expires, refresh()
// trades the cookie for a new access token. A 401 from the API triggers one refresh and one retry.

import { useSyncExternalStore } from 'react'

export type Role = 'USER' | 'ADMIN'
export type User = { id: number; email: string; role: Role }
export type AuthState = { status: 'loading' } | { status: 'signedOut' } | { status: 'signedIn'; user: User }

type SignedIn = { accessToken: string; tokenType: string; expiresIn: number; user: User }

let state: AuthState = { status: 'loading' }
let accessToken: string | null = null
let renewTimer: ReturnType<typeof setTimeout> | undefined
let refreshing: Promise<boolean> | null = null
const listeners = new Set<() => void>()

function set(next: AuthState) {
  state = next
  listeners.forEach((l) => l())
}

/** The current sign-in state, re-rendering the component when it changes. */
export function useAuth(): AuthState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => state,
  )
}

function accept(body: SignedIn) {
  accessToken = body.accessToken
  clearTimeout(renewTimer)
  // renew a minute before it runs out (never sooner than in 10 s)
  renewTimer = setTimeout(() => void refresh(), Math.max(10, body.expiresIn - 60) * 1000)
  set({ status: 'signedIn', user: body.user })
}

function forget() {
  accessToken = null
  clearTimeout(renewTimer)
  set({ status: 'signedOut' })
}

async function errorOf(response: Response): Promise<string> {
  try {
    const body = await response.json()
    return body.error ?? body.detail ?? `${response.status} ${response.statusText}`
  } catch {
    return response.status === 429 ? 'Too many attempts, please wait a minute' : `${response.status} ${response.statusText}`
  }
}

async function post(path: string, body?: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
}

export async function signIn(email: string, password: string): Promise<void> {
  const response = await post('/api/v1/auth/login', { email, password })
  if (!response.ok) throw new Error(await errorOf(response))
  accept(await response.json())
}

export async function signUp(email: string, password: string): Promise<void> {
  const response = await post('/api/v1/auth/signup', { email, password })
  if (!response.ok) throw new Error(await errorOf(response))
  accept(await response.json())
}

/** Trades the refresh cookie for a new access token. Calls at the same time share one request. */
export function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const response = await post('/api/v1/auth/refresh')
      if (!response.ok) {
        forget()
        return false
      }
      accept(await response.json())
      return true
    } catch {
      // network trouble: keep a token we still have, else show the sign-in screen
      if (!accessToken) forget()
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

export async function signOut(everywhere = false): Promise<void> {
  try {
    if (everywhere && accessToken) {
      await fetch('/api/v1/auth/logout-all', { method: 'POST', headers: { Authorization: `Bearer ${accessToken}` } })
    } else {
      await post('/api/v1/auth/logout')
    }
  } finally {
    forget()
  }
}

/** fetch() with the access token; on a 401 it refreshes once and retries, and signs out when that fails too. */
export async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const send = () =>
    fetch(path, { ...init, headers: { ...init.headers, ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) } })
  let response = await send()
  if (response.status === 401 && (await refresh())) {
    response = await send()
  }
  return response
}

export { errorOf }
