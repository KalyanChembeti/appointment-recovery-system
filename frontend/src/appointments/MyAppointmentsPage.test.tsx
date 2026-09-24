import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AppointmentResponse,
  AppointmentTypeResponse,
  ProviderListResponse,
  TimeSlot,
} from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authState'
import { MyAppointmentsPage } from './MyAppointmentsPage'

const IDLE_STATE = { loading: false, error: null }
const PROVIDERS: ProviderListResponse[] = [
  { id: 21, userId: 31, specialtyId: 1, displayName: 'Dr. Rivera' },
]
const APPOINTMENT_TYPES: AppointmentTypeResponse[] = [
  { id: 11, name: 'Consultation', durationMinutes: 30, specialtyId: 1 },
]
const SLOT: TimeSlot = {
  startAt: '2040-02-10T15:00:00Z',
  endAt: '2040-02-10T15:30:00Z',
}

function appointment(overrides: Partial<AppointmentResponse> = {}): AppointmentResponse {
  return {
    id: 101,
    patientId: 71,
    providerId: 21,
    appointmentTypeId: 11,
    startAt: '2040-01-10T14:00:00Z',
    endAt: '2040-01-10T14:30:00Z',
    status: 'SCHEDULED',
    ...overrides,
  }
}

type FetchScenario = {
  appointments?: AppointmentResponse[]
  providers?: ProviderListResponse[]
  appointmentTypes?: AppointmentTypeResponse[]
  availabilityResponses?: TimeSlot[][]
  rescheduleError?: { code: string; message: string }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetchMock({
  appointments = [appointment()],
  providers = PROVIDERS,
  appointmentTypes = APPOINTMENT_TYPES,
  availabilityResponses = [[SLOT]],
  rescheduleError,
}: FetchScenario = {}) {
  let availabilityRequest = 0

  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input.toString()
    if (path === '/api/appointments' && (init?.method ?? 'GET') === 'GET') {
      return jsonResponse(appointments)
    }
    if (path === '/api/providers') return jsonResponse(providers)
    if (path === '/api/appointment-types') return jsonResponse(appointmentTypes)
    if (path.startsWith('/api/availability?')) {
      const response = availabilityResponses[
        Math.min(availabilityRequest, availabilityResponses.length - 1)
      ]
      availabilityRequest += 1
      return jsonResponse(response)
    }
    if (path.endsWith('/cancel') && init?.method === 'POST') {
      return jsonResponse(appointment({ status: 'CANCELLED' }))
    }
    if (path.endsWith('/reschedule') && init?.method === 'POST') {
      return rescheduleError
        ? jsonResponse(rescheduleError, 409)
        : jsonResponse(appointment({ id: 202, startAt: SLOT.startAt, endAt: SLOT.endAt }))
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
        <MyAppointmentsPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

function actionRequest(
  fetchMock: ReturnType<typeof createFetchMock>,
  suffix: '/cancel' | '/reschedule',
) {
  return fetchMock.mock.calls.find(
    ([path, init]) => typeof path === 'string'
      && path.endsWith(suffix)
      && init?.method === 'POST',
  )
}

async function openCancellation() {
  const user = userEvent.setup()
  const entry = await screen.findByRole('article', { name: 'Consultation' })
  await user.click(within(entry).getByRole('button', { name: 'Cancel' }))
  return { user, entry }
}

async function selectRescheduleSlot() {
  const user = userEvent.setup()
  const entry = await screen.findByRole('article', { name: 'Consultation' })
  await user.click(within(entry).getByRole('button', { name: 'Reschedule' }))
  fireEvent.change(within(entry).getByLabelText('New appointment date'), {
    target: { value: '2040-02-10' },
  })
  await user.click(await within(entry).findByRole('button', { name: /^Select / }))
  return { user, entry }
}

describe('MyAppointmentsPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=appointments-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('splits and sorts scheduled appointments ascending and all others descending', async () => {
    const types = [
      { id: 11, name: 'Early Visit', durationMinutes: 30, specialtyId: 1 },
      { id: 12, name: 'Late Visit', durationMinutes: 30, specialtyId: 1 },
      { id: 13, name: 'Recent Cancelled Visit', durationMinutes: 30, specialtyId: 1 },
      { id: 14, name: 'Older Completed Visit', durationMinutes: 30, specialtyId: 1 },
    ]
    const fetchMock = createFetchMock({
      appointmentTypes: types,
      appointments: [
        appointment({ id: 2, appointmentTypeId: 12, startAt: '2040-01-20T14:00:00Z' }),
        appointment({ id: 4, appointmentTypeId: 14, startAt: '2040-01-05T14:00:00Z', status: 'COMPLETED' }),
        appointment({ id: 1, appointmentTypeId: 11, startAt: '2040-01-10T14:00:00Z' }),
        appointment({ id: 3, appointmentTypeId: 13, startAt: '2040-01-18T14:00:00Z', status: 'CANCELLED' }),
      ],
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const upcoming = await screen.findByRole('region', { name: 'Upcoming' })
    const past = screen.getByRole('region', { name: 'Past' })
    expect(within(upcoming).getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent)).toEqual(['Early Visit', 'Late Visit'])
    expect(within(past).getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent))
      .toEqual(['Recent Cancelled Visit', 'Older Completed Visit'])
  })

  it('submits the entered cancellation reason', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const { user, entry } = await openCancellation()
    await user.type(within(entry).getByLabelText('Reason (optional)'), 'Schedule changed')
    await user.click(within(entry).getByRole('button', { name: 'Confirm cancellation' }))

    await waitFor(() => expect(actionRequest(fetchMock, '/cancel')).toBeDefined())
    expect(JSON.parse(String(actionRequest(fetchMock, '/cancel')?.[1]?.body)))
      .toEqual({ reasonText: 'Schedule changed' })
  })

  it('omits reasonText when a cancellation reason is blank', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const { user, entry } = await openCancellation()
    await user.click(within(entry).getByRole('button', { name: 'Confirm cancellation' }))

    await waitFor(() => expect(actionRequest(fetchMock, '/cancel')).toBeDefined())
    expect(JSON.parse(String(actionRequest(fetchMock, '/cancel')?.[1]?.body))).toEqual({})
  })

  it('does not offer cancel or reschedule actions for a non-scheduled appointment', async () => {
    vi.stubGlobal('fetch', createFetchMock({
      appointments: [appointment({ status: 'NO_SHOW' })],
    }))
    renderPage()

    await screen.findByRole('article', { name: 'Consultation' })
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reschedule' })).not.toBeInTheDocument()
  })

  it('reschedules with the original provider and appointment type and only a new startAt', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const { user, entry } = await selectRescheduleSlot()
    expect(entry).toHaveTextContent('Rescheduling with Dr. Rivera, Consultation.')
    await user.click(within(entry).getByRole('button', { name: 'Confirm reschedule' }))

    await waitFor(() => expect(actionRequest(fetchMock, '/reschedule')).toBeDefined())
    expect(JSON.parse(String(actionRequest(fetchMock, '/reschedule')?.[1]?.body))).toEqual({
      providerId: 21,
      appointmentTypeId: 11,
      startAt: SLOT.startAt,
    })
  })

  it('shows the race message and refreshes only the slot picker after a double booking', async () => {
    const fetchMock = createFetchMock({
      availabilityResponses: [[SLOT], []],
      rescheduleError: {
        code: 'PROVIDER_DOUBLE_BOOKED',
        message: 'Provider is already booked',
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const { user, entry } = await selectRescheduleSlot()
    await user.click(within(entry).getByRole('button', { name: 'Confirm reschedule' }))

    expect(await within(entry).findByRole('alert')).toHaveTextContent(
      'This time is no longer available. Please choose another time.',
    )
    await waitFor(() => {
      expect(fetchMock.mock.calls.filter(
        ([path]) => typeof path === 'string' && path.startsWith('/api/availability?'),
      )).toHaveLength(2)
    })
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/appointments'))
      .toHaveLength(1)
    expect(await within(entry).findByText('No available times for this date.'))
      .toBeInTheDocument()
  })
})
