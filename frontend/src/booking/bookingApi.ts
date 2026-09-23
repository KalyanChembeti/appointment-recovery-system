import { apiRequest } from '../api/client'
import type { AppointmentResponse, BookAppointmentRequest } from '../api/types'

export const bookingApi = {
  book: (request: BookAppointmentRequest) => apiRequest<AppointmentResponse>(
    '/api/appointments',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  ),
}
