import { apiRequest } from '../api/client'
import type { PatientSearchResponse } from '../api/types'

export const patientSearchApi = {
  search: (query: string) => apiRequest<PatientSearchResponse[]>(
    `/api/patients?query=${encodeURIComponent(query)}`,
  ),
}
