import { apiRequest } from '../api/client'
import type {
  AppointmentResponse,
  CancelAppointmentRequest,
  RescheduleAppointmentRequest,
} from '../api/types'

export const appointmentsApi = {
  cancel: (appointmentId: number, reasonText?: string) => {
    const request: CancelAppointmentRequest = reasonText ? { reasonText } : {}
    return apiRequest<AppointmentResponse>(`/api/appointments/${appointmentId}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
  },
  reschedule: (appointmentId: number, request: RescheduleAppointmentRequest) =>
    apiRequest<AppointmentResponse>(`/api/appointments/${appointmentId}/reschedule`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
}
