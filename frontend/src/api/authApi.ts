import { apiRequest } from './client'
import type {
  CurrentUserResponse,
  LoginRequest,
  LoginResponse,
  LogoutResponse,
  RegisterRequest,
  RegisterResponse,
} from './types'

function jsonRequest(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export const authApi = {
  currentUser: () => apiRequest<CurrentUserResponse>('/api/auth/me'),
  login: (request: LoginRequest) =>
    apiRequest<LoginResponse>('/api/auth/login', jsonRequest(request)),
  register: (request: RegisterRequest) =>
    apiRequest<RegisterResponse>('/api/auth/register', jsonRequest(request)),
  logout: () =>
    apiRequest<LogoutResponse>('/api/auth/logout', { method: 'POST' }),
}
