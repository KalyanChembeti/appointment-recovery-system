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

export type SpecialtyResponse = {
  id: number
  name: string
  description: string | null
}

export type AppointmentTypeResponse = {
  id: number
  name: string
  durationMinutes: number
  specialtyId: number
}

export type ProviderListResponse = {
  id: number
  userId: number
  specialtyId: number
  displayName: string | null
}

export type TimeSlot = {
  startAt: string
  endAt: string
}

export type BookAppointmentRequest = {
  providerId: number
  appointmentTypeId: number
  startAt: string
}

export type CancelAppointmentRequest = {
  reasonText?: string
}

export type RescheduleAppointmentRequest = {
  providerId: number
  appointmentTypeId: number
  startAt: string
}

export type AppointmentResponse = {
  id: number
  patientId: number
  providerId: number
  appointmentTypeId: number
  startAt: string
  endAt: string
  status: string
}
