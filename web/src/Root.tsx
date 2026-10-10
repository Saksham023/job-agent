import { useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect } from 'react'
import App from './App'
import { AuthScreen } from './components/AuthScreen'
import { refresh, useAuth } from './lib/auth'

/** Everything is behind sign-in: restore the session from the refresh cookie, else show the sign-in screen. */
export default function Root() {
  const auth = useAuth()
  const queryClient = useQueryClient()

  useEffect(() => {
    void refresh()
  }, [])

  // nothing from one account may be shown to the next one
  useEffect(() => {
    if (auth.status === 'signedOut') queryClient.clear()
  }, [auth.status, queryClient])

  if (auth.status === 'loading') {
    return (
      <div className="grid min-h-screen place-items-center">
        <Loader2 className="animate-spin text-violet-500" size={28} aria-label="Loading" />
      </div>
    )
  }
  if (auth.status === 'signedOut') return <AuthScreen />
  return <App user={auth.user} />
}
