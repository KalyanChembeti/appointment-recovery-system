import { createContext, useContext } from 'react'
import type { ApiError, UserRole } from '../api/types'

export type AuthenticatedUser = { userId: number; role: UserRole }
export type OperationState = { loading: boolean; error: ApiError | null }
export type AuthContextValue = {
  user: AuthenticatedUser | null
  loginState: OperationState
  registerState: OperationState
  logoutState: OperationState
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
