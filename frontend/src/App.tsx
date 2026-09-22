import './App.css'
import { AuthProvider } from './auth/AuthContext'
import { LoginPage } from './auth/LoginPage'

function App() {
  return (
    <AuthProvider>
      <LoginPage />
    </AuthProvider>
  )
}

export default App
