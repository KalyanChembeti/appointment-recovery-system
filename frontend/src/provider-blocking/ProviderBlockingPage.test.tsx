import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AppointmentResponse,
  ProviderListResponse,
  ProviderUnavailabilityResponse,
  TimeSlot,
} from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authState'
import { ProviderBlockingPage } from './ProviderBlockingPage'

const IDLE_STATE = { loading: false, error: null }
const PROVIDERS: ProviderListResponse[] = [
  { id: 21, userId: 31, specialtyId: 1, displayName: 'Dr. Smith' },
  { id: 22, userId: 32, specialtyId: 1, displayName: null },
]
const FIRST_CONFLICT: AppointmentResponse = {
  id: 501,
  patientId: 71,
  providerId: 21,
  appointmentTypeId: 11,
  startAt: '2040-09-15T18:30:00Z',
  endAt: '2040-09-15T19:15:00Z',
  status: 'SCHEDULED',
  patientDisplayName: 'Patient X',
}
const SECOND_CONFLICT: AppointmentResponse = {
  id: 502,
  patientId: 72,
  providerId: 21,
  appointmentTypeId: 12,
  startAt: '2040-09-15T19:30:00Z',
  endAt: '2040-09-15T20:00:00Z',
  status: 'SCHEDULED',
  patientDisplayName: null,
}
const SLOT: TimeSlot = {
  startAt: '2040-09-16T15:00:00Z',
  endAt: '2040-09-16T15:45:00Z',
}

function block(
  status: ProviderUnavailabilityResponse['status'],
  conflictingAppointmentIds: number[] = [],
): ProviderUnavailabilityResponse {
  return {
    id: 301,
    providerId: 21,
    startAt: '2040-09-15T18:00:00Z',
    endAt: '2040-09-15T21:00:00Z',
    status,
    reason: 'Conference',
    conflictingAppointmentIds,
  }
}

type ApiFailure = {
  status: number
  code: string
  message: string
}

type FetchScenario = {
  createResponse?: ProviderUnavailabilityResponse
  conflicts?: AppointmentResponse[]
  availability?: TimeSlot[]
  activationError?: ApiFailure
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetchMock({
  createResponse = block('ACTIVE'),
  conflicts = [FIRST_CONFLICT],
  availability = [SLOT],
  activationError,
}: FetchScenario = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input.toString()
    const method = init?.method ?? 'GET'

    if (path === '/api/providers' && method === 'GET') return jsonResponse(PROVIDERS)
    if (path === '/api/provider-unavailability' && method === 'POST') {
      return jsonResponse(createResponse, 201)
    }
    if (path.startsWith('/api/appointments/') && method === 'GET') {
      const appointmentId = Number(path.split('/').at(-1))
      const appointment = conflicts.find((candidate) => candidate.id === appointmentId)
      if (!appointment) throw new Error(`Missing conflict fixture for appointment ${appointmentId}`)
      return jsonResponse(appointment)
    }
    if (path.startsWith('/api/availability?') && method === 'GET') {
      return jsonResponse(availability)
    }
    if (path === '/api/appointments/501/reschedule' && method === 'POST') {
      return jsonResponse({ ...FIRST_CONFLICT, startAt: SLOT.startAt, endAt: SLOT.endAt })
    }
    if (path === '/api/appointments/501/cancel' && method === 'POST') {
      return jsonResponse({ ...FIRST_CONFLICT, status: 'CANCELLED' })
    }
    if (path === '/api/provider-unavailability/301/activate' && method === 'POST') {
      return activationError
        ? jsonResponse(activationError, activationError.status)
        : jsonResponse(block('ACTIVE'))
    }
    if (path === '/api/provider-unavailability/301/cancel' && method === 'POST') {
      return jsonResponse(block('CANCELLED'))
    }
    throw new Error(`Unexpected request: ${method} ${path}`)
  })
}

function renderPage() {
  const authValue: AuthContextValue = {
    user: { userId: 91, role: 'ADMIN' },
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
        <ProviderBlockingPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

async function submitCreationForm() {
  const user = userEvent.setup()
  await user.selectOptions(
    screen.getByLabelText('Provider'),
    await screen.findByRole('option', { name: 'Dr. Smith' }),
  )
  fireEvent.change(screen.getByLabelText('Start date and time'), {
    target: { value: '2040-09-15T14:00' },
  })
  fireEvent.change(screen.getByLabelText('End date and time'), {
    target: { value: '2040-09-15T17:00' },
  })
  await user.type(screen.getByLabelText('Reason (optional)'), 'Conference')
  await user.click(screen.getByRole('button', { name: 'Create time block' }))
  return user
}

function postRequest(fetchMock: ReturnType<typeof createFetchMock>, path: string) {
  return fetchMock.mock.calls.find(
    ([requestPath, init]) => requestPath === path && init?.method === 'POST',
  )
}

function formatDate(instant: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(instant))
}

function formatTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(instant))
}

describe('ProviderBlockingPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=provider-blocking-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('shows direct success when the created block is active', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await submitCreationForm()

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Time block created and active.',
    )
    expect(screen.queryByRole('heading', { name: 'Resolve conflicting appointments' }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Provider #22' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(
      ([path]) => typeof path === 'string' && path.startsWith('/api/appointments/'),
    )).toBe(false)
  })

  it('converts datetime-local creation values to exact ISO instants', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await submitCreationForm()

    const request = postRequest(fetchMock, '/api/provider-unavailability')
    expect(request).toBeDefined()
    const requestBody = JSON.parse(String(request?.[1]?.body))
    expect(requestBody.startAt).toBe(new Date('2040-09-15T14:00').toISOString())
    expect(requestBody.endAt).toBe(new Date('2040-09-15T17:00').toISOString())
  })
  it('fetches and displays each pending conflict with patient, date, and time', async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501, 502]),
      conflicts: [FIRST_CONFLICT, SECOND_CONFLICT],
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await submitCreationForm()

    expect(await screen.findByRole('heading', { name: 'Resolve conflicting appointments' }))
      .toBeInTheDocument()
    expect(screen.getByText('This block conflicts with 2 appointment(s):')).toBeInTheDocument()
    const first = await screen.findByRole('article', { name: 'Patient X' })
    expect(first).toHaveTextContent(formatDate(FIRST_CONFLICT.startAt))
    expect(first).toHaveTextContent(formatTime(FIRST_CONFLICT.startAt))
    expect(first).toHaveTextContent(formatTime(FIRST_CONFLICT.endAt))
    const second = await screen.findByRole('article', { name: 'Patient #72' })
    expect(second).toHaveTextContent(formatDate(SECOND_CONFLICT.startAt))
    expect(fetchMock).toHaveBeenCalledWith('/api/appointments/501', expect.any(Object))
    expect(fetchMock).toHaveBeenCalledWith('/api/appointments/502', expect.any(Object))
  })

  it('reschedules a conflict with its original provider and appointment type', async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501]),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const user = await submitCreationForm()
    const conflict = await screen.findByRole('article', { name: 'Patient X' })
    await user.click(within(conflict).getByRole('button', {
      name: 'Reschedule this appointment',
    }))
    fireEvent.change(within(conflict).getByLabelText('New appointment date'), {
      target: { value: '2040-09-16' },
    })
    await user.click(await within(conflict).findByRole('button', { name: /^Select / }))
    await user.click(within(conflict).getByRole('button', { name: 'Confirm reschedule' }))

    await waitFor(() => {
      expect(postRequest(fetchMock, '/api/appointments/501/reschedule')).toBeDefined()
    })
    expect(JSON.parse(String(
      postRequest(fetchMock, '/api/appointments/501/reschedule')?.[1]?.body,
    ))).toEqual({
      providerId: FIRST_CONFLICT.providerId,
      appointmentTypeId: FIRST_CONFLICT.appointmentTypeId,
      startAt: SLOT.startAt,
    })
    expect(await within(conflict).findByText('Rescheduled')).toBeInTheDocument()
    expect(within(conflict).queryByRole('button', { name: 'Cancel this appointment' }))
      .not.toBeInTheDocument()
  })

  it('cancels a conflicting appointment after inline confirmation', async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501]),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const user = await submitCreationForm()
    const conflict = await screen.findByRole('article', { name: 'Patient X' })
    await user.click(within(conflict).getByRole('button', { name: 'Cancel this appointment' }))
    await user.type(within(conflict).getByLabelText('Reason (optional)'), 'Provider unavailable')
    await user.click(within(conflict).getByRole('button', { name: 'Confirm cancellation' }))

    await waitFor(() => {
      expect(postRequest(fetchMock, '/api/appointments/501/cancel')).toBeDefined()
    })
    expect(JSON.parse(String(
      postRequest(fetchMock, '/api/appointments/501/cancel')?.[1]?.body,
    ))).toEqual({ reasonText: 'Provider unavailable' })
    expect(await within(conflict).findByText('Cancelled')).toBeInTheDocument()
  })

  it("cancels the pending block and returns to the creation form for don't create", async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501]),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const user = await submitCreationForm()
    await screen.findByRole('article', { name: 'Patient X' })
    await user.click(screen.getByRole('button', { name: "Don't create this time block" }))
    expect(screen.getByText('Cancel this pending time block?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {
      name: "Confirm: don't create this time block",
    }))

    await waitFor(() => {
      expect(postRequest(fetchMock, '/api/provider-unavailability/301/cancel')).toBeDefined()
    })
    expect(await screen.findByRole('status')).toHaveTextContent('Time block cancelled.')
    expect(screen.getByLabelText('Provider')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Resolve conflicting appointments' }))
      .not.toBeInTheDocument()
  })

  it('shows the backend conflict message when activation is rejected and keeps the view', async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501]),
      activationError: {
        status: 409,
        code: 'PROVIDER_BLOCK_CONFLICTS_UNRESOLVED',
        message: 'Conflicting appointments must be resolved before activation.',
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const user = await submitCreationForm()
    await screen.findByRole('article', { name: 'Patient X' })
    await user.click(screen.getByRole('button', { name: 'Activate this time block' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Conflicting appointments must be resolved before activation.',
    )
    expect(screen.getByRole('heading', { name: 'Resolve conflicting appointments' }))
      .toBeInTheDocument()
  })

  it('shows confirmation after the backend activates the pending block', async () => {
    const fetchMock = createFetchMock({
      createResponse: block('PENDING', [501]),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const user = await submitCreationForm()
    await screen.findByRole('article', { name: 'Patient X' })
    await user.click(screen.getByRole('button', { name: 'Activate this time block' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Time block is now active.',
    )
    expect(screen.queryByRole('heading', { name: 'Resolve conflicting appointments' }))
      .not.toBeInTheDocument()
  })
})