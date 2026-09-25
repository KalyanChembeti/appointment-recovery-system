import type { PropsWithChildren } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { HomePage } from './HomePage'
import { AuthProvider } from './auth/AuthContext'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { useAuth } from './auth/authState'
import { BookingPage } from './booking/BookingPage'
import { MyAppointmentsPage } from './appointments/MyAppointmentsPage'
import { Card } from './components/Card'
import { LoadingState } from './components/LoadingState'
import { PageLayout } from './components/PageLayout'
import { MyOffersPage } from './offers/MyOffersPage'
import { ProviderBlockingPage } from './provider-blocking/ProviderBlockingPage'
import { ProtectedRoute } from './routing/ProtectedRoute'

function PublicOnlyRoute({ children }: PropsWithChildren) {
  const { user, isInitializing } = useAuth()
  if (isInitializing) return <LoadingState />
  return user ? <Navigate to="/" replace /> : children
}

function NotAuthorizedPage() {
  return (
    <PageLayout>
      <div className="mx-auto max-w-xl py-10">
        <Card>
          <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">Access restricted</p>
          <h1 id="not-authorized-heading" className="text-3xl font-bold tracking-tight text-ink">Not authorized</h1>
          <p className="mt-3 text-muted">Your account cannot access this page.</p>
        </Card>
      </div>
    </PageLayout>
  )
}

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />
          <Route path="/register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
          <Route path="/not-authorized" element={<NotAuthorizedPage />} />
          <Route
            path="/book"
            element={(
              <ProtectedRoute allowedRoles={['PATIENT']}>
                <BookingPage />
              </ProtectedRoute>
            )}
          />
          <Route
            path="/appointments"
            element={(
              <ProtectedRoute allowedRoles={['PATIENT']}>
                <MyAppointmentsPage />
              </ProtectedRoute>
            )}
          />
          <Route
            path="/offers"
            element={(
              <ProtectedRoute allowedRoles={['PATIENT']}>
                <MyOffersPage />
              </ProtectedRoute>
            )}
          />
          <Route
            path="/provider-blocking"
            element={(
              <ProtectedRoute allowedRoles={['ADMIN']}>
                <ProviderBlockingPage />
              </ProtectedRoute>
            )}
          />
          <Route path="/" element={<ProtectedRoute><HomePage /></ProtectedRoute>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}

export default App
