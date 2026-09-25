import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AppointmentResponse,
  PatientSearchResponse,
  TimeSlot,
} from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authState'
import { ReceptionistBookingPage } from './ReceptionistBookingPage'

const IDLE_STATE = { loading: false, error: null }
const FIRST_PATIENT: PatientSearchResponse = {
  id: 71,
  email: 'ava.morgan@example.com',
  displayName: 'Ava Morgan',
}
const SECOND_PATIENT: PatientSearchResponse = {
  id: 72,
  email: 'ben.lee@example.com',
  displayName: 'Ben Lee',
}
const SPECIALTIES = [{ id: 1, name: 'Cardiology', description: null }]
const APPOINTMENT_TYPES = [{
  id: 11,
  name: 'Consultation',
  durationMinutes: 30,
  specialtyId: 1,
}]
const PROVIDERS = [{
  id: 21,
  userId: 31,
  specialtyId: 1,
  displayName: 'Dr. Rivera',
}]
const SLOT: TimeSlot = {
  startAt: '2040-01-10T14:00:00Z',
  endAt: '2040-01-10T14:30:00Z',
}
const APPOINTMENT: AppointmentResponse = {
  id: 901,
  patientId: FIRST_PATIENT.id,
  providerId: 21,
  appointmentTypeId: 11,
  startAt: SLOT.startAt,
  endAt: SLOT.endAt,
  status: 'SCHEDULED',
  patientDisplayName: FIRST_PATIENT.displayName,
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetchMock() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input.toString()
    if (path === '/api/patients?query=ava') return jsonResponse([FIRST_PATIENT])
    if (path === '/api/patients?query=ben') return jsonResponse([SECOND_PATIENT])
    if (path === '/api/specialties') return jsonResponse(SPECIALTIES)
    if (path === '/api/appointment-types?specialtyId=1') {
      return jsonResponse(APPOINTMENT_TYPES)
    }
    if (path === '/api/providers?specialtyId=1') return jsonResponse(PROVIDERS)
    if (path.startsWith('/api/availability?')) return jsonResponse([SLOT])
    if (path === '/api/appointments' && init?.method === 'POST') {
      return jsonResponse(APPOINTMENT, 201)
    }
    throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${path}`)
  })
}

function renderPage() {
  const authValue: AuthContextValue = {
    user: { userId: 91, role: 'RECEPTIONIST' },
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
        <ReceptionistBookingPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

async function searchForPatient(query = 'ava') {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Patient email or name'), query)
  await user.click(screen.getByRole('button', { name: 'Search' }))
  return user
}

async function selectFirstPatient() {
  const user = await searchForPatient()
  await user.click(await screen.findByRole('button', {
    name: /ava\.morgan@example\.com/i,
  }))
  return user
}

async function bookForSelectedPatient() {
  const user = await selectFirstPatient()
  await screen.findByRole('option', { name: 'Cardiology' })
  await user.selectOptions(screen.getByLabelText('Specialty'), '1')
  await screen.findByRole('option', { name: 'Consultation (30 min)' })
  await user.selectOptions(screen.getByLabelText('Appointment type'), '11')
  await screen.findByRole('option', { name: 'Dr. Rivera' })
  await user.selectOptions(screen.getByLabelText('Provider'), '21')
  fireEvent.change(screen.getByLabelText('Appointment date'), {
    target: { value: '2040-01-10' },
  })
  await user.click(await screen.findByRole('button', { name: /^Select / }))
  await user.click(screen.getByRole('button', { name: 'Confirm booking' }))
  return user
}

function bookingRequest(fetchMock: ReturnType<typeof createFetchMock>) {
  return fetchMock.mock.calls.find(
    ([path, init]) => path === '/api/appointments' && init?.method === 'POST',
  )
}

describe('ReceptionistBookingPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=receptionist-booking-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('renders the shared booking form after selection and returns to search on change', async () => {
    vi.stubGlobal('fetch', createFetchMock())
    renderPage()
    const user = await selectFirstPatient()

    expect(screen.getByText('Selected patient')).toBeInTheDocument()
    expect(screen.getByText(FIRST_PATIENT.email)).toBeInTheDocument()
    expect(screen.getByLabelText('Specialty')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Change patient' }))
    expect(screen.getByRole('heading', { name: 'Find a patient' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Specialty')).not.toBeInTheDocument()
  })

  it('books with the selected patient ID and confirms that patient identity', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await bookForSelectedPatient()

    await waitFor(() => expect(bookingRequest(fetchMock)).toBeDefined())
    expect(JSON.parse(String(bookingRequest(fetchMock)?.[1]?.body))).toEqual({
      patientId: FIRST_PATIENT.id,
      providerId: 21,
      appointmentTypeId: 11,
      startAt: SLOT.startAt,
    })
    expect(await screen.findByRole('heading', { name: 'Appointment booked' }))
      .toBeInTheDocument()
    expect(screen.getByText(FIRST_PATIENT.email)).toBeInTheDocument()
    expect(screen.getByText('Ava Morgan')).toBeInTheDocument()
  })

  it('resets for another booking and supports another search in the same render', async () => {
    const fetchMock = createFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    const user = await bookForSelectedPatient()

    await user.click(await screen.findByRole('button', { name: 'Book another appointment' }))
    expect(screen.getByRole('heading', { name: 'Find a patient' })).toBeInTheDocument()

    await user.type(screen.getByLabelText('Patient email or name'), 'ben')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText(SECOND_PATIENT.email)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/patients?query=ben', expect.any(Object))
  })
})
