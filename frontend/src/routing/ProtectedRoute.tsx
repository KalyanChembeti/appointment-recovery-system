import type { PropsWithChildren } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import type { UserRole } from '../api/types'
import { useAuth } from '../auth/authState'

type ProtectedRouteProps = PropsWithChildren<{
  allowedRoles?: readonly UserRole[]
}>

export function ProtectedRoute({ children, allowedRoles }: ProtectedRouteProps) {
  const { user, isInitializing } = useAuth()
  const location = useLocation()

  if (isInitializing) {
    return <p role="status">Loading...</p>
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to="/not-authorized" replace />
  }
  return children
}
