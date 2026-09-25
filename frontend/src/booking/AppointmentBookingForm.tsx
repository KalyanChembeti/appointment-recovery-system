import { useState, type ChangeEvent } from 'react'
import { isApiError } from '../api/client'
import type {
  ApiError,
  AppointmentResponse,
  AppointmentTypeResponse,
  ProviderListResponse,
  SpecialtyResponse,
  TimeSlot,
} from '../api/types'
import { useApiQuery } from '../api/useApiQuery'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { AvailabilitySlotPicker } from './AvailabilitySlotPicker'
import { bookingApi } from './bookingApi'

const BOOKING_RACE_CODES = new Set([
  'PROVIDER_DOUBLE_BOOKED',
  'PATIENT_DOUBLE_BOOKED',
])
const BOOKING_RACE_MESSAGE =
  'This time is no longer available. Please choose another time.'

type AppointmentBookingFormProps = {
  patientId?: number
  onBooked: (appointment: AppointmentResponse) => void
  onProviderSelected?: (provider: ProviderListResponse) => void
}

function formatDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

function providerLabel(provider: ProviderListResponse) {
  // This fallback is presentation-only. The null backend value remains unchanged, and the
  // generated label is never stored in or sent through a booking request.
  return provider.displayName ?? `Provider #${provider.id}`
}

function unexpectedError(): ApiError {
  return { message: 'An unexpected error prevented the request from completing.' }
}

export function AppointmentBookingForm({
  patientId,
  onBooked,
  onProviderSelected,
}: AppointmentBookingFormProps) {
  const [specialtyId, setSpecialtyId] = useState('')
  const [appointmentTypeId, setAppointmentTypeId] = useState('')
  const [providerId, setProviderId] = useState('')
  const [date, setDate] = useState('')
  const [selectedSlot, setSelectedSlot] = useState<TimeSlot | null>(null)
  const [booking, setBooking] = useState(false)
  const [bookingError, setBookingError] = useState<ApiError | null>(null)
  const [availabilityRefreshKey, setAvailabilityRefreshKey] = useState(0)

  const specialtiesQuery = useApiQuery<SpecialtyResponse[]>('/api/specialties', [])
  const appointmentTypesPath: `/api/${string}` | null = specialtyId
    ? `/api/appointment-types?specialtyId=${encodeURIComponent(specialtyId)}`
    : null
  const providersPath: `/api/${string}` | null = specialtyId
    ? `/api/providers?specialtyId=${encodeURIComponent(specialtyId)}`
    : null
  const appointmentTypesQuery = useApiQuery<AppointmentTypeResponse[]>(
    appointmentTypesPath,
    [specialtyId],
  )
  const providersQuery = useApiQuery<ProviderListResponse[]>(providersPath, [specialtyId])
  const selectedSpecialty = specialtiesQuery.data?.find(
    (specialty) => specialty.id === Number(specialtyId),
  )
  const selectedAppointmentType = appointmentTypesQuery.data?.find(
    (appointmentType) => appointmentType.id === Number(appointmentTypeId),
  )
  const selectedProvider = providersQuery.data?.find(
    (provider) => provider.id === Number(providerId),
  )
  const selectedProviderLabel = selectedProvider
    ? providerLabel(selectedProvider)
    : `Provider #${providerId}`

  function changeSpecialty(event: ChangeEvent<HTMLSelectElement>) {
    setSpecialtyId(event.target.value)
    setAppointmentTypeId('')
    setProviderId('')
    setDate('')
    setSelectedSlot(null)
    setBookingError(null)
  }

  function changeAppointmentType(event: ChangeEvent<HTMLSelectElement>) {
    setAppointmentTypeId(event.target.value)
    setDate('')
    setSelectedSlot(null)
    setBookingError(null)
  }

  function changeProvider(event: ChangeEvent<HTMLSelectElement>) {
    setProviderId(event.target.value)
    setDate('')
    setSelectedSlot(null)
    setBookingError(null)
  }

  async function confirmBooking() {
    if (!selectedSlot || !providerId || !appointmentTypeId) return

    setBooking(true)
    setBookingError(null)
    try {
      const appointment = await bookingApi.book({
        ...(patientId === undefined ? {} : { patientId }),
        providerId: Number(providerId),
        appointmentTypeId: Number(appointmentTypeId),
        startAt: selectedSlot.startAt,
      })
      if (selectedProvider) onProviderSelected?.(selectedProvider)
      onBooked(appointment)
    } catch (error) {
      const apiError = isApiError(error) ? error : unexpectedError()
      if (apiError.status === 409 && apiError.code && BOOKING_RACE_CODES.has(apiError.code)) {
        setBookingError({ ...apiError, message: BOOKING_RACE_MESSAGE })
        setSelectedSlot(null)
        setAvailabilityRefreshKey((key) => key + 1)
      } else {
        setBookingError(apiError)
      }
    } finally {
      setBooking(false)
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <label className="block text-sm font-semibold text-ink" htmlFor="specialty">
          Specialty
        </label>
        <select
          id="specialty"
          className="mt-2 block min-h-12 w-full rounded-xl border border-border bg-surface px-3.5 py-2.5 text-base text-ink shadow-xs outline-none transition focus:border-brand focus:ring-4 focus:ring-brand/15"
          value={specialtyId}
          onChange={changeSpecialty}
          disabled={specialtiesQuery.loading}
        >
          <option value="">
            {specialtiesQuery.loading ? 'Loading specialties...' : 'Choose a specialty'}
          </option>
          {specialtiesQuery.data?.map((specialty) => (
            <option key={specialty.id} value={specialty.id}>{specialty.name}</option>
          ))}
        </select>
        {specialtiesQuery.error && (
          <p className="mt-3 text-sm font-medium text-danger" role="alert">
            {specialtiesQuery.error.message}
          </p>
        )}
      </Card>

      {specialtyId && (
        <Card>
          <h2 className="text-xl font-bold text-ink">Choose the visit and provider</h2>
          <p className="mt-1 text-sm text-muted">Options for {selectedSpecialty?.name}.</p>
          <div className="mt-5 grid gap-5 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-semibold text-ink" htmlFor="appointment-type">
                Appointment type
              </label>
              <select
                id="appointment-type"
                className="mt-2 block min-h-12 w-full rounded-xl border border-border bg-surface px-3.5 py-2.5 text-base text-ink shadow-xs outline-none transition focus:border-brand focus:ring-4 focus:ring-brand/15"
                value={appointmentTypeId}
                onChange={changeAppointmentType}
                disabled={appointmentTypesQuery.loading}
              >
                <option value="">
                  {appointmentTypesQuery.loading ? 'Loading visit types...' : 'Choose a visit type'}
                </option>
                {appointmentTypesQuery.data?.map((appointmentType) => (
                  <option key={appointmentType.id} value={appointmentType.id}>
                    {appointmentType.name} ({appointmentType.durationMinutes} min)
                  </option>
                ))}
              </select>
              {appointmentTypesQuery.error && (
                <p className="mt-3 text-sm font-medium text-danger" role="alert">
                  {appointmentTypesQuery.error.message}
                </p>
              )}
            </div>

            <div>
              <label className="block text-sm font-semibold text-ink" htmlFor="provider">
                Provider
              </label>
              <select
                id="provider"
                className="mt-2 block min-h-12 w-full rounded-xl border border-border bg-surface px-3.5 py-2.5 text-base text-ink shadow-xs outline-none transition focus:border-brand focus:ring-4 focus:ring-brand/15"
                value={providerId}
                onChange={changeProvider}
                disabled={providersQuery.loading}
              >
                <option value="">
                  {providersQuery.loading ? 'Loading providers...' : 'Choose a provider'}
                </option>
                {providersQuery.data?.map((provider) => (
                  <option key={provider.id} value={provider.id}>
                    {providerLabel(provider)}
                  </option>
                ))}
              </select>
              {providersQuery.error && (
                <p className="mt-3 text-sm font-medium text-danger" role="alert">
                  {providersQuery.error.message}
                </p>
              )}
            </div>
          </div>
        </Card>
      )}

      {specialtyId && appointmentTypeId && providerId && (
        <Card>
          <FormField
            id="appointment-date"
            name="appointment-date"
            type="date"
            label="Appointment date"
            value={date}
            onChange={(event) => {
              setDate(event.target.value)
              setSelectedSlot(null)
              setBookingError(null)
            }}
            required
          />
        </Card>
      )}

      {date && (
        <Card>
          <h2 className="text-xl font-bold text-ink">Choose a time</h2>
          <p className="mt-1 text-sm text-muted">
            Available times for {date} with {selectedProviderLabel}.
          </p>
          <AvailabilitySlotPicker
            key={availabilityRefreshKey}
            providerId={Number(providerId)}
            appointmentTypeId={Number(appointmentTypeId)}
            date={date}
            selectedSlot={selectedSlot}
            onSelectSlot={(slot) => {
              setSelectedSlot(slot)
              setBookingError(null)
            }}
          />
        </Card>
      )}

      {selectedSlot && (
        <Card>
          <h2 className="text-xl font-bold text-ink">Confirm your appointment</h2>
          <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt className="font-medium text-muted">Specialty</dt>
              <dd className="font-semibold text-ink">{selectedSpecialty?.name}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted">Visit</dt>
              <dd className="font-semibold text-ink">{selectedAppointmentType?.name}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted">Provider</dt>
              <dd className="font-semibold text-ink">{selectedProviderLabel}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted">Date and time</dt>
              <dd className="font-semibold text-ink">{formatDateTime(selectedSlot.startAt)}</dd>
            </div>
          </dl>
          <Button
            className="mt-6 w-full sm:w-auto"
            type="button"
            loading={booking}
            loadingText="Booking..."
            onClick={() => void confirmBooking()}
          >
            Confirm booking
          </Button>
        </Card>
      )}

      {bookingError && (
        <p className="rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
          {bookingError.message}
        </p>
      )}
    </div>
  )
}
