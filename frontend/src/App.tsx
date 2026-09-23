import type { PropsWithChildren } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import './App.css'
import { HomePage } from './HomePage'
import { AuthProvider } from './auth/AuthContext'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { useAuth } from './auth/authState'
import { ProtectedRoute } from './routing/ProtectedRoute'

function PublicOnlyRoute({ children }: PropsWithChildren) {
  const { user, isInitializing } = useAuth()
  if (isInitializing) return <p role="status">Loading...</p>
  return user ? <Navigate to="/" replace /> : children
}

function NotAuthorizedPage() {
  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="not-authorized-heading">
        <h1 id="not-authorized-heading">Not authorized</h1>
        <p className="supporting-copy">Your account cannot access this page.</p>
      </section>
    </main>
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
          <Route path="/" element={<ProtectedRoute><HomePage /></ProtectedRoute>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}

export default App
