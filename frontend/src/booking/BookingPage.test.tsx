import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AppointmentResponse,
  ProviderListResponse,
  TimeSlot,
} from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authState'
import { BookingPage } from './BookingPage'

const IDLE_STATE = { loading: false, error: null }
const SPECIALTIES = [{ id: 1, name: 'Cardiology', description: null }]
const APPOINTMENT_TYPES = [{
  id: 11,
  name: 'Consultation',
  durationMinutes: 30,
  specialtyId: 1,
}]
const SLOT: TimeSlot = {
  startAt: '2040-01-10T14:00:00Z',
  endAt: '2040-01-10T14:30:00Z',
}
const APPOINTMENT: AppointmentResponse = {
  id: 901,
  patientId: 71,
  providerId: 21,
  appointmentTypeId: 11,
  startAt: SLOT.startAt,
  endAt: SLOT.endAt,
  status: 'SCHEDULED',
  patientDisplayName: 'Booking Patient',
}

type FetchScenario = {
  provider?: ProviderListResponse
  availabilityResponses?: TimeSlot[][]
  bookingResponse?: AppointmentResponse
  bookingError?: { code: string; message: string }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetchMock({
  provider = { id: 21, userId: 31, specialtyId: 1, displayName: 'Dr. Rivera' },
  availabilityResponses = [[SLOT]],
  bookingResponse = APPOINTMENT,
  bookingError,
}: FetchScenario = {}) {
  let availabilityRequest = 0

  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input.toString()
    if (path === '/api/specialties') return jsonResponse(SPECIALTIES)
    if (path === '/api/appointment-types?specialtyId=1') {
      return jsonResponse(APPOINTMENT_TYPES)
    }
    if (path === '/api/providers?specialtyId=1') return jsonResponse([provider])
    if (path.startsWith('/api/availability?')) {
      const response = availabilityResponses[
        Math.min(availabilityRequest, availabilityResponses.length - 1)
      ]
      availabilityRequest += 1
      return jsonResponse(response)
    }
    if (path === '/api/appointments' && init?.method === 'POST') {
      return bookingError
        ? jsonResponse(bookingError, 409)
        : jsonResponse(bookingResponse, 201)
    }
    throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${path}`)
  })
}

function renderBookingPage() {
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
        <BookingPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

async function selectThroughAvailableSlot() {
  const user = userEvent.setup()
  await screen.findByRole('option', { name: 'Cardiology' })
  await user.selectOptions(screen.getByLabelText('Specialty'), '1')
  await screen.findByRole('option', { name: 'Consultation (30 min)' })
  await user.selectOptions(screen.getByLabelText('Appointment type'), '11')
  await screen.findByRole('option', { name: /Provider #21|Dr\. Rivera/ })
  await user.selectOptions(screen.getByLabelText('Provider'), '21')
  fireEvent.change(screen.getByLabelText('Appointment date'), {
    target: { value: '2040-01-10' },
  })
  await user.click(await screen.findByRole('button', { name: /^Select / }))
  return user
}

function bookingRequest(fetchMock: ReturnType<typeof createFetchMock>) {
  return fetchMock.mock.calls.find(
    ([path, init]) => path === '/api/appointments' && init?.method === 'POST',
  )
}

describe('BookingPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=booking-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('fetches appointment types and providers filtered by the selected specialty', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderBookingPage()

    await user.selectOptions(
      screen.getByLabelText('Specialty'),
      await screen.findByRole('option', { name: 'Cardiology' }),
    )

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/appointment-types?specialtyId=1',
        expect.any(Object),
      )
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/providers?specialtyId=1',
        expect.any(Object),
      )
    })
  })

  it('renders a UI-only provider fallback without sending it in the booking request', async () => {
    const fetchMock = createFetchMock({
      provider: { id: 21, userId: 31, specialtyId: 1, displayName: null },
    })
    vi.stubGlobal('fetch', fetchMock)
    renderBookingPage()

    const user = await selectThroughAvailableSlot()
    expect(screen.getByRole('option', { name: 'Provider #21' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }))

    await waitFor(() => expect(bookingRequest(fetchMock)).toBeDefined())
    const requestBody = JSON.parse(String(bookingRequest(fetchMock)?.[1]?.body))
    expect(requestBody).toEqual({
      providerId: 21,
      appointmentTypeId: 11,
      startAt: SLOT.startAt,
    })
    expect(JSON.stringify(requestBody)).not.toContain('Provider #21')
  })

  it('books the selected slot with no patientId field', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderBookingPage()

    const user = await selectThroughAvailableSlot()
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }))

    await waitFor(() => expect(bookingRequest(fetchMock)).toBeDefined())
    expect(JSON.parse(String(bookingRequest(fetchMock)?.[1]?.body))).toEqual({
      providerId: 21,
      appointmentTypeId: 11,
      startAt: SLOT.startAt,
    })
    expect(JSON.parse(String(bookingRequest(fetchMock)?.[1]?.body)))
      .not.toHaveProperty('patientId')
  })

  it('shows the booking-race message and refreshes availability after a double booking', async () => {
    const fetchMock = createFetchMock({
      availabilityResponses: [[SLOT], []],
      bookingError: {
        code: 'PROVIDER_DOUBLE_BOOKED',
        message: 'Provider is already booked',
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    renderBookingPage()

    const user = await selectThroughAvailableSlot()
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This time is no longer available. Please choose another time.',
    )
    await waitFor(() => {
      const availabilityCalls = fetchMock.mock.calls.filter(
        ([path]) => typeof path === 'string' && path.startsWith('/api/availability?'),
      )
      expect(availabilityCalls).toHaveLength(2)
    })
    expect(await screen.findByText('No available times for this date.')).toBeInTheDocument()
  })

  it('shows the confirmed appointment details after a successful booking', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderBookingPage()

    const user = await selectThroughAvailableSlot()
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByRole('heading', { name: /booked/i })).toBeInTheDocument()
    expect(screen.getByText('#901')).toBeInTheDocument()
    expect(screen.getByText('scheduled')).toBeInTheDocument()
    expect(screen.getByText('Dr. Rivera')).toBeInTheDocument()
    expect(screen.getByText(new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(SLOT.startAt)))).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/')
  })
})
