import { useState } from 'react'
import { isApiError } from '../api/client'
import type {
  ApiError,
  AppointmentResponse,
  AppointmentTypeResponse,
  ProviderListResponse,
  TimeSlot,
} from '../api/types'
import { useApiQuery } from '../api/useApiQuery'
import { AvailabilitySlotPicker } from '../booking/AvailabilitySlotPicker'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { PageLayout } from '../components/PageLayout'
import { appointmentsApi } from './appointmentsApi'

const BOOKING_RACE_CODES = new Set([
  'PROVIDER_DOUBLE_BOOKED',
  'PATIENT_DOUBLE_BOOKED',
])
const BOOKING_RACE_MESSAGE =
  'This time is no longer available. Please choose another time.'

type AppointmentAction = 'cancel' | 'reschedule' | null

function formatDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

function providerLabel(provider: ProviderListResponse | undefined, providerId: number) {
  // Missing provider display names remain null in API data; this fallback is render-only.
  return provider?.displayName ?? `Provider #${providerId}`
}

function appointmentTypeLabel(
  appointmentType: AppointmentTypeResponse | undefined,
  appointmentTypeId: number,
) {
  return appointmentType?.name ?? `Appointment type #${appointmentTypeId}`
}

function normalizedError(error: unknown): ApiError {
  return isApiError(error)
    ? error
    : { message: 'An unexpected error prevented the request from completing.' }
}

function statusClasses(status: string) {
  if (status === 'SCHEDULED') return 'bg-brand-soft text-brand-deep'
  if (status === 'CANCELLED' || status === 'NO_SHOW') {
    return 'bg-danger/10 text-danger'
  }
  return 'bg-soft text-muted'
}

function statusLabel(status: string) {
  return status.replaceAll('_', ' ').toLowerCase()
}

type AppointmentEntryProps = {
  appointment: AppointmentResponse
  providerName: string
  appointmentTypeName: string
  onListChanged: () => void
}

function AppointmentEntry({
  appointment,
  providerName,
  appointmentTypeName,
  onListChanged,
}: AppointmentEntryProps) {
  const [action, setAction] = useState<AppointmentAction>(null)
  const [reasonText, setReasonText] = useState('')
  const [rescheduleDate, setRescheduleDate] = useState('')
  const [selectedSlot, setSelectedSlot] = useState<TimeSlot | null>(null)
  const [slotPickerKey, setSlotPickerKey] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const [actionError, setActionError] = useState<ApiError | null>(null)
  const scheduled = appointment.status === 'SCHEDULED'

  function closeAction() {
    setAction(null)
    setReasonText('')
    setRescheduleDate('')
    setSelectedSlot(null)
    setActionError(null)
  }

  function openAction(nextAction: Exclude<AppointmentAction, null>) {
    closeAction()
    setAction(nextAction)
  }

  async function confirmCancellation() {
    setSubmitting(true)
    setActionError(null)
    try {
      const reason = reasonText.trim() ? reasonText : undefined
      await appointmentsApi.cancel(appointment.id, reason)
      closeAction()
      onListChanged()
    } catch (error) {
      setActionError(normalizedError(error))
    } finally {
      setSubmitting(false)
    }
  }

  async function confirmReschedule() {
    if (!selectedSlot) return

    setSubmitting(true)
    setActionError(null)
    try {
      await appointmentsApi.reschedule(appointment.id, {
        providerId: appointment.providerId,
        appointmentTypeId: appointment.appointmentTypeId,
        startAt: selectedSlot.startAt,
      })
      closeAction()
      onListChanged()
    } catch (error) {
      const apiError = normalizedError(error)
      if (apiError.status === 409 && apiError.code && BOOKING_RACE_CODES.has(apiError.code)) {
        setActionError({ ...apiError, message: BOOKING_RACE_MESSAGE })
        setSelectedSlot(null)
        setSlotPickerKey((key) => key + 1)
      } else {
        setActionError(apiError)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card
      role="article"
      aria-labelledby={`appointment-${appointment.id}-heading`}
      className="p-5 sm:p-6"
    >
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div>
          <h3 id={`appointment-${appointment.id}-heading`} className="text-lg font-bold text-ink">
            {appointmentTypeName}
          </h3>
          <p className="mt-1 font-medium text-muted">{providerName}</p>
          <p className="mt-2 text-sm text-muted">{formatDateTime(appointment.startAt)}</p>
        </div>
        <span
          className={`w-fit rounded-full px-3 py-1 text-xs font-bold uppercase tracking-wide ${statusClasses(appointment.status)}`}
        >
          {statusLabel(appointment.status)}
        </span>
      </div>

      {scheduled && action === null && (
        <div className="mt-5 flex flex-wrap gap-3">
          <Button type="button" variant="danger" onClick={() => openAction('cancel')}>
            Cancel
          </Button>
          <Button type="button" variant="secondary" onClick={() => openAction('reschedule')}>
            Reschedule
          </Button>
        </div>
      )}

      {action === 'cancel' && (
        <div className="mt-6 border-t border-border pt-5">
          <h4 className="font-bold text-ink">Cancel this appointment?</h4>
          <div className="mt-4">
            <FormField
              label="Reason (optional)"
              value={reasonText}
              onChange={(event) => setReasonText(event.target.value)}
            />
          </div>
          <div className="mt-4 flex flex-wrap gap-3">
            <Button
              type="button"
              variant="danger"
              loading={submitting}
              loadingText="Cancelling..."
              onClick={() => void confirmCancellation()}
            >
              Confirm cancellation
            </Button>
            <Button type="button" variant="secondary" disabled={submitting} onClick={closeAction}>
              Back
            </Button>
          </div>
        </div>
      )}

      {action === 'reschedule' && (
        <div className="mt-6 border-t border-border pt-5">
          <h4 className="font-bold text-ink">Choose a new time</h4>
          <p className="mt-1 text-sm text-muted">
            Rescheduling with {providerName}, {appointmentTypeName}.
          </p>
          <div className="mt-4">
            <FormField
              type="date"
              label="New appointment date"
              value={rescheduleDate}
              onChange={(event) => {
                setRescheduleDate(event.target.value)
                setSelectedSlot(null)
                setActionError(null)
              }}
              required
            />
          </div>
          {rescheduleDate && (
            <AvailabilitySlotPicker
              key={slotPickerKey}
              providerId={appointment.providerId}
              appointmentTypeId={appointment.appointmentTypeId}
              date={rescheduleDate}
              selectedSlot={selectedSlot}
              onSelectSlot={(slot) => {
                setSelectedSlot(slot)
                setActionError(null)
              }}
            />
          )}
          {selectedSlot && (
            <p className="mt-4 text-sm font-semibold text-ink">
              New time: {formatDateTime(selectedSlot.startAt)}
            </p>
          )}
          <div className="mt-4 flex flex-wrap gap-3">
            <Button
              type="button"
              loading={submitting}
              loadingText="Rescheduling..."
              disabled={!selectedSlot}
              onClick={() => void confirmReschedule()}
            >
              Confirm reschedule
            </Button>
            <Button type="button" variant="secondary" disabled={submitting} onClick={closeAction}>
              Back
            </Button>
          </div>
        </div>
      )}

      {actionError && (
        <p
          className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger"
          role="alert"
        >
          {actionError.message}
        </p>
      )}
    </Card>
  )
}

export function MyAppointmentsPage() {
  const appointmentsQuery = useApiQuery<AppointmentResponse[]>('/api/appointments', [])
  const providersQuery = useApiQuery<ProviderListResponse[]>('/api/providers', [])
  const appointmentTypesQuery = useApiQuery<AppointmentTypeResponse[]>(
    '/api/appointment-types',
    [],
  )

  const providersById = new Map(
    providersQuery.data?.map((provider) => [provider.id, provider]) ?? [],
  )
  const appointmentTypesById = new Map(
    appointmentTypesQuery.data?.map((appointmentType) => [appointmentType.id, appointmentType])
      ?? [],
  )
  const upcoming = (appointmentsQuery.data ?? [])
    .filter((appointment) => appointment.status === 'SCHEDULED')
    .sort((first, second) => Date.parse(first.startAt) - Date.parse(second.startAt))
  const past = (appointmentsQuery.data ?? [])
    .filter((appointment) => appointment.status !== 'SCHEDULED')
    .sort((first, second) => Date.parse(second.startAt) - Date.parse(first.startAt))
  const loading = appointmentsQuery.loading
    || providersQuery.loading
    || appointmentTypesQuery.loading
  const pageErrors = [
    appointmentsQuery.error,
    providersQuery.error,
    appointmentTypesQuery.error,
  ].filter((error): error is ApiError => error !== null)

  function entry(appointment: AppointmentResponse) {
    return (
      <AppointmentEntry
        key={appointment.id}
        appointment={appointment}
        providerName={providerLabel(
          providersById.get(appointment.providerId),
          appointment.providerId,
        )}
        appointmentTypeName={appointmentTypeLabel(
          appointmentTypesById.get(appointment.appointmentTypeId),
          appointment.appointmentTypeId,
        )}
        onListChanged={appointmentsQuery.refetch}
      />
    )
  }

  return (
    <PageLayout>
      <div className="mx-auto max-w-4xl py-4 sm:py-8">
        <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
          Patient scheduling
        </p>
        <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
          My appointments
        </h1>
        <p className="mt-3 leading-7 text-muted">
          Review your scheduled appointments and appointment history.
        </p>

        {loading && (
          <p className="mt-8 text-sm font-medium text-muted" role="status">
            Loading appointments...
          </p>
        )}
        {pageErrors.map((error, index) => (
          <p
            key={`${error.code ?? 'error'}-${index}`}
            className="mt-5 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger"
            role="alert"
          >
            {error.message}
          </p>
        ))}

        {!loading && pageErrors.length === 0 && (
          <div className="mt-8 space-y-10">
            <section aria-labelledby="upcoming-heading">
              <h2 id="upcoming-heading" className="text-2xl font-bold text-ink">Upcoming</h2>
              <div className="mt-4 space-y-4">
                {upcoming.length > 0
                  ? upcoming.map(entry)
                  : <p className="text-sm text-muted">No upcoming appointments.</p>}
              </div>
            </section>

            <section aria-labelledby="past-heading">
              <h2 id="past-heading" className="text-2xl font-bold text-ink">Past</h2>
              <div className="mt-4 space-y-4">
                {past.length > 0
                  ? past.map(entry)
                  : <p className="text-sm text-muted">No past appointments.</p>}
              </div>
            </section>
          </div>
        )}
      </div>
    </PageLayout>
  )
}
