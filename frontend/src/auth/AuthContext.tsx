import {
  useEffect,
  useState,
  type PropsWithChildren,
} from 'react'
import { authApi } from '../api/authApi'
import { isApiError } from '../api/client'
import type { ApiError } from '../api/types'
import { AuthContext, type AuthenticatedUser, type OperationState } from './authState'

const IDLE_STATE: OperationState = { loading: false, error: null }
function toApiError(error: unknown): ApiError {
  return isApiError(error)
    ? error
    : { message: 'An unexpected error prevented the request from completing.' }
}

async function fetchAuthenticatedUser(): Promise<AuthenticatedUser | null> {
  try {
    return await authApi.currentUser()
  } catch (error) {
    if (isApiError(error) && error.status === 401) return null
    throw error
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  // This is only an in-memory mirror. The real identity remains in the HttpOnly SESSION
  // cookie and is restored from the backend whenever this provider mounts.
  const [user, setUser] = useState<AuthenticatedUser | null>(null)
  const [isInitializing, setIsInitializing] = useState(true)
  const [loginState, setLoginState] = useState<OperationState>(IDLE_STATE)
  const [registerState, setRegisterState] = useState<OperationState>(IDLE_STATE)
  const [logoutState, setLogoutState] = useState<OperationState>(IDLE_STATE)

  useEffect(() => {
    let active = true
    void fetchAuthenticatedUser()
      .then((currentUser) => {
        if (active) setUser(currentUser)
      })
      .catch(() => {
        if (active) setUser(null)
      })
      .finally(() => {
        if (active) setIsInitializing(false)
      })
    return () => { active = false }
  }, [])

  async function login(email: string, password: string) {
    setLoginState({ loading: true, error: null })
    try {
      await authApi.login({ email, password })
      setUser(await fetchAuthenticatedUser())
      setLoginState(IDLE_STATE)
    } catch (error) {
      setLoginState({ loading: false, error: toApiError(error) })
      throw error
    }
  }

  async function register(email: string, password: string) {
    setRegisterState({ loading: true, error: null })
    try {
      await authApi.register({ email, password })
      setUser(await fetchAuthenticatedUser())
      setRegisterState(IDLE_STATE)
    } catch (error) {
      setRegisterState({ loading: false, error: toApiError(error) })
      throw error
    }
  }

  async function logout() {
    setLogoutState({ loading: true, error: null })
    try {
      await authApi.logout()
      setUser(null)
      setLogoutState(IDLE_STATE)
    } catch (error) {
      setLogoutState({ loading: false, error: toApiError(error) })
      throw error
    }
  }

  const value = {
    user,
    isInitializing,
    loginState,
    registerState,
    logoutState,
    login,
    register,
    logout,
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
