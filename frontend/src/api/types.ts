export type ApiError = {
  status?: number
  code?: string
  message: string
  timestamp?: string
  path?: string
  errors?: Record<string, string>
}

export type UserRole = 'PATIENT' | 'PROVIDER' | 'RECEPTIONIST' | 'ADMIN'

export type RegisterRequest = { email: string; password: string }

export type RegisterResponse = {
  userId: number
  email: string
  role: UserRole
}

export type LoginRequest = { email: string; password: string }

export type LoginResponse = { userId: number; authenticated: boolean }

export type CurrentUserResponse = { userId: number; role: UserRole }

export type LogoutResponse = { status: string }
