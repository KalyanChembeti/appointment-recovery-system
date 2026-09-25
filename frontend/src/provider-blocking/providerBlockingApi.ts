import { apiRequest } from '../api/client'
import type {
  AppointmentResponse,
  ProviderUnavailabilityResponse,
  RequestProviderBlockRequest,
} from '../api/types'

export const providerBlockingApi = {
  create: (request: RequestProviderBlockRequest) =>
    apiRequest<ProviderUnavailabilityResponse>('/api/provider-unavailability', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  activate: (blockId: number) =>
    apiRequest<ProviderUnavailabilityResponse>(
      `/api/provider-unavailability/${blockId}/activate`,
      { method: 'POST' },
    ),
  cancel: (blockId: number) =>
    apiRequest<ProviderUnavailabilityResponse>(
      `/api/provider-unavailability/${blockId}/cancel`,
      { method: 'POST' },
    ),
  findConflictingAppointments: (appointmentIds: number[]) => Promise.all(
    appointmentIds.map((appointmentId) => apiRequest<AppointmentResponse>(
      `/api/appointments/${appointmentId}`,
    )),
  ),
}