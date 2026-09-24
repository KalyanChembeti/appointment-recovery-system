import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AcceptOfferResponse,
  AppointmentTypeResponse,
  ProviderListResponse,
  SlotOfferResponse,
} from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authState'
import { MyOffersPage } from './MyOffersPage'

const IDLE_STATE = { loading: false, error: null }
const PROVIDERS: ProviderListResponse[] = [
  { id: 21, userId: 31, specialtyId: 1, displayName: 'Dr. Offered' },
  { id: 22, userId: 32, specialtyId: 1, displayName: 'Dr. Second' },
  { id: 99, userId: 109, specialtyId: 1, displayName: 'Dr. Prior Appointment' },
]
const APPOINTMENT_TYPES: AppointmentTypeResponse[] = [
  { id: 11, name: 'Earlier Visit', durationMinutes: 30, specialtyId: 1 },
  { id: 12, name: 'Later Visit', durationMinutes: 30, specialtyId: 1 },
  { id: 13, name: 'Accepted Visit', durationMinutes: 30, specialtyId: 1 },
  { id: 14, name: 'Declined Visit', durationMinutes: 30, specialtyId: 1 },
]

function offer(overrides: Partial<SlotOfferResponse> = {}): SlotOfferResponse {
  return {
    id: 101,
    recoveryJobId: 201,
    waitlistEntryId: 301,
    status: 'OFFERED',
    expiresAt: '2040-06-01T18:00:00Z',
    acceptedAt: null,
    providerId: 21,
    appointmentTypeId: 11,
    startAt: '2040-06-10T14:00:00Z',
    endAt: '2040-06-10T14:30:00Z',
    ...overrides,
  }
}

const ACCEPTED_APPOINTMENT: AcceptOfferResponse = {
  slotOfferId: 101,
  status: 'ACCEPTED',
  appointmentId: 901,
  patientId: 71,
  providerId: 21,
  appointmentTypeId: 11,
  startAt: '2040-06-10T14:00:00Z',
  endAt: '2040-06-10T14:30:00Z',
}

type ApiFailure = {
  status: number
  code: string
  message: string
}

type FetchScenario = {
  offersResponses?: SlotOfferResponse[][]
  providers?: ProviderListResponse[]
  appointmentTypes?: AppointmentTypeResponse[]
  acceptResponse?: AcceptOfferResponse
  acceptError?: ApiFailure
  declineError?: ApiFailure
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetchMock({
  offersResponses = [[offer()]],
  providers = PROVIDERS,
  appointmentTypes = APPOINTMENT_TYPES,
  acceptResponse = ACCEPTED_APPOINTMENT,
  acceptError,
  declineError,
}: FetchScenario = {}) {
  let offersRequest = 0

  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input.toString()
    if (path === '/api/slot-offers' && (init?.method ?? 'GET') === 'GET') {
      const response = offersResponses[
        Math.min(offersRequest, offersResponses.length - 1)
      ]
      offersRequest += 1
      return jsonResponse(response)
    }
    if (path === '/api/providers') return jsonResponse(providers)
    if (path === '/api/appointment-types') return jsonResponse(appointmentTypes)
    if (path.endsWith('/accept') && init?.method === 'POST') {
      return acceptError
        ? jsonResponse(acceptError, acceptError.status)
        : jsonResponse(acceptResponse)
    }
    if (path.endsWith('/decline') && init?.method === 'POST') {
      return declineError
        ? jsonResponse(declineError, declineError.status)
        : jsonResponse(offer({ status: 'DECLINED' }))
    }
    throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${path}`)
  })
}

function renderPage() {
  const authValue: AuthContextValue = {
    user: { userId: 71, role: 'PATIENT' },
    isInitializing: false,
    loginState: IDLE_STATE,
    registerState: IDLE_STATE,
    logoutState: IDLE_STATE,
    login: vi.fn(async () => undefined),
    register: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined),
  }

  render(
    <AuthContext.Provider value={authValue}>
      <MemoryRouter>
        <MyOffersPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

function offerListRequests(fetchMock: ReturnType<typeof createFetchMock>) {
  return fetchMock.mock.calls.filter(
    ([path, init]) => path === '/api/slot-offers' && (init?.method ?? 'GET') === 'GET',
  )
}

function actionRequest(
  fetchMock: ReturnType<typeof createFetchMock>,
  suffix: '/accept' | '/decline',
) {
  return fetchMock.mock.calls.find(
    ([path, init]) => typeof path === 'string'
      && path.endsWith(suffix)
      && init?.method === 'POST',
  )
}

function formattedDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

function formattedTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(new Date(instant))
}

describe('MyOffersPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=offers-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('splits and sorts offers and displays the offered slot and provider', async () => {
    const offeredStart = '2040-06-10T14:00:00Z'
    const offeredEnd = '2040-06-10T14:30:00Z'
    vi.stubGlobal('fetch', createFetchMock({
      offersResponses: [[
        offer({
          id: 102,
          appointmentTypeId: 12,
          expiresAt: '2040-06-01T20:00:00Z',
        }),
        offer({ id: 104, appointmentTypeId: 14, status: 'DECLINED' }),
        offer({
          id: 101,
          appointmentTypeId: 11,
          expiresAt: '2040-06-01T18:00:00Z',
          startAt: offeredStart,
          endAt: offeredEnd,
        }),
        offer({ id: 103, appointmentTypeId: 13, status: 'ACCEPTED' }),
      ]],
    }))
    renderPage()

    const active = await screen.findByRole('region', { name: 'Active' })
    const resolved = screen.getByRole('region', { name: 'Resolved' })
    expect(within(active).getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent)).toEqual(['Earlier Visit', 'Later Visit'])
    expect(within(resolved).getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent)).toEqual(['Declined Visit', 'Accepted Visit'])

    const offeredEntry = within(active).getByRole('article', { name: 'Earlier Visit' })
    expect(offeredEntry).toHaveTextContent('Dr. Offered')
    expect(offeredEntry).not.toHaveTextContent('Dr. Prior Appointment')
    expect(offeredEntry).toHaveTextContent(
      `${formattedDateTime(offeredStart)} – ${formattedTime(offeredEnd)}`,
    )
    expect(offeredEntry).toHaveTextContent(
      `Expires ${formattedDateTime('2040-06-01T18:00:00Z')}`,
    )
  })

  it('does not show accept or decline actions for a resolved offer', async () => {
    vi.stubGlobal('fetch', createFetchMock({
      offersResponses: [[offer({ status: 'EXPIRED' })]],
    }))
    renderPage()

    const entry = await screen.findByRole('article', { name: 'Earlier Visit' })
    expect(within(entry).queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(within(entry).queryByRole('button', { name: 'Decline' })).not.toBeInTheDocument()
  })

  it('accepts with an empty self-booking body, refetches, and shows appointment details', async () => {
    const fetchMock = createFetchMock({
      offersResponses: [[offer()], [offer({ status: 'ACCEPTED' })]],
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(offerListRequests(fetchMock)).toHaveLength(2))
    const request = actionRequest(fetchMock, '/accept')
    expect(request).toBeDefined()
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({})

    const confirmationHeading = await screen.findByText('Offer accepted')
    const confirmation = confirmationHeading.parentElement as HTMLElement
    expect(confirmation).toHaveTextContent('Appointment #901')
    expect(confirmation).toHaveTextContent('Dr. Offered')
    expect(confirmation).toHaveTextContent(
      `${formattedDateTime(ACCEPTED_APPOINTMENT.startAt)} – ${formattedTime(ACCEPTED_APPOINTMENT.endAt)}`,
    )
    expect(within(confirmation).getByRole('link', { name: 'View my appointments' }))
      .toHaveAttribute('href', '/appointments')
  })

  it.each([
    {
      name: 'already-resolved',
      error: {
        status: 409,
        code: 'OFFER_ALREADY_RESOLVED',
        message: 'Offer was already resolved',
      },
      expectedMessage: 'This offer is no longer available.',
      refreshedStatus: 'DECLINED' as const,
      expectedListRequests: 2,
    },
    {
      name: 'expired',
      error: {
        status: 410,
        code: 'OFFER_EXPIRED',
        message: 'Offer expired on the server',
      },
      expectedMessage: 'This offer has expired.',
      refreshedStatus: 'EXPIRED' as const,
      expectedListRequests: 2,
    },
    {
      name: 'generic',
      error: {
        status: 500,
        code: 'INTERNAL_ERROR',
        message: 'Please try again later.',
      },
      expectedMessage: 'Please try again later.',
      refreshedStatus: 'OFFERED' as const,
      expectedListRequests: 1,
    },
  ])('shows the distinct $name accept failure message and refetches when required', async ({
    error,
    expectedMessage,
    refreshedStatus,
    expectedListRequests,
  }) => {
    const fetchMock = createFetchMock({
      offersResponses: [[offer()], [offer({ status: refreshedStatus })]],
      acceptError: error,
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Accept' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(expectedMessage)
    await waitFor(() => {
      expect(offerListRequests(fetchMock)).toHaveLength(expectedListRequests)
    })
  })

  it('uses inline decline confirmation, submits, and refetches after success', async () => {
    const fetchMock = createFetchMock({
      offersResponses: [[offer()], [offer({ status: 'DECLINED' })]],
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()

    const entry = await screen.findByRole('article', { name: 'Earlier Visit' })
    await user.click(within(entry).getByRole('button', { name: 'Decline' }))
    expect(within(entry).getByText('Decline this offer?')).toBeInTheDocument()
    expect(actionRequest(fetchMock, '/decline')).toBeUndefined()

    await user.click(within(entry).getByRole('button', { name: 'Back' }))
    expect(within(entry).queryByText('Decline this offer?')).not.toBeInTheDocument()
    await user.click(within(entry).getByRole('button', { name: 'Decline' }))
    await user.click(within(entry).getByRole('button', { name: 'Confirm decline' }))

    await waitFor(() => expect(actionRequest(fetchMock, '/decline')).toBeDefined())
    await waitFor(() => expect(offerListRequests(fetchMock)).toHaveLength(2))
  })
})
