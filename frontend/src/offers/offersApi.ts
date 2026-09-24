import { apiRequest } from '../api/client'
import type { AcceptOfferResponse, SlotOfferResponse } from '../api/types'

export const offersApi = {
  accept: (offerId: number) => apiRequest<AcceptOfferResponse>(
    `/api/slot-offers/${offerId}/accept`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    },
  ),
  decline: (offerId: number) => apiRequest<SlotOfferResponse>(
    `/api/slot-offers/${offerId}/decline`,
    { method: 'POST' },
  ),
}
