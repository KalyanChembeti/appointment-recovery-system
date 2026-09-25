/* oxlint-disable react/set-state-in-effect */
import { useEffect, useState, type FormEvent } from 'react'
import { isApiError } from '../api/client'
import type {
  ApiError,
  AppointmentResponse,
  ProviderListResponse,
  ProviderUnavailabilityResponse,
  TimeSlot,
} from '../api/types'
import { useApiQuery } from '../api/useApiQuery'
import { appointmentsApi } from '../appointments/appointmentsApi'
import { AvailabilitySlotPicker } from '../booking/AvailabilitySlotPicker'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { PageLayout } from '../components/PageLayout'
import { providerBlockingApi } from './providerBlockingApi'

function normalizedError(error: unknown): ApiError {
  return isApiError(error)
    ? error
    : { message: 'An unexpected error prevented the request from completing.' }
}

function providerLabel(provider: ProviderListResponse) {
  return provider.displayName ?? `Provider #${provider.id}`
}

function patientLabel(appointment: AppointmentResponse) {
  return appointment.patientDisplayName ?? `Patient #${appointment.patientId}`
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

function localDateTimeToInstant(value: string) {
  return new Date(value).toISOString()
}

type ConflictOutcome = 'Rescheduled' | 'Cancelled'
type ConflictAction = 'reschedule' | 'cancel' | null

type ConflictAppointmentProps = {
  appointment: AppointmentResponse
  outcome?: ConflictOutcome
  onResolved: (outcome: ConflictOutcome) => void
}

function ConflictAppointment({
  appointment,
  outcome,
  onResolved,
}: ConflictAppointmentProps) {
  const [action, setAction] = useState<ConflictAction>(null)
  const [rescheduleDate, setRescheduleDate] = useState('')
  const [selectedSlot, setSelectedSlot] = useState<TimeSlot | null>(null)
  const [reasonText, setReasonText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const headingId = `conflict-${appointment.id}-heading`

  function closeAction() {
    setAction(null)
    setRescheduleDate('')
    setSelectedSlot(null)
    setReasonText('')
    setError(null)
  }

  function openAction(nextAction: Exclude<ConflictAction, null>) {
    closeAction()
    setAction(nextAction)
  }

  async function confirmReschedule() {
    if (!selectedSlot) return

    setSubmitting(true)
    setError(null)
    try {
      await appointmentsApi.reschedule(appointment.id, {
        providerId: appointment.providerId,
        appointmentTypeId: appointment.appointmentTypeId,
        startAt: selectedSlot.startAt,
      })
      onResolved('Rescheduled')
    } catch (requestError) {
      setError(normalizedError(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  async function confirmCancellation() {
    setSubmitting(true)
    setError(null)
    try {
      const reason = reasonText.trim() || undefined
      await appointmentsApi.cancel(appointment.id, reason)
      onResolved('Cancelled')
    } catch (requestError) {
      setError(normalizedError(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card role="article" aria-labelledby={headingId} className="p-5 sm:p-6">
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
        <div>
          <h3 id={headingId} className="text-lg font-bold text-ink">
            {patientLabel(appointment)}
          </h3>
          <p className="mt-2 text-sm font-medium text-muted">
            {formatDate(appointment.startAt)}, {formatTime(appointment.startAt)}–{formatTime(appointment.endAt)}
          </p>
        </div>
        {outcome && (
          <span className="w-fit rounded-full bg-brand-soft px-3 py-1 text-xs font-bold uppercase tracking-wide text-brand-deep">
            {outcome}
          </span>
        )}
      </div>

      {!outcome && action === null && (
        <div className="mt-5 flex flex-wrap gap-3">
          <Button type="button" variant="secondary" onClick={() => openAction('reschedule')}>
            Reschedule this appointment
          </Button>
          <Button type="button" variant="danger" onClick={() => openAction('cancel')}>
            Cancel this appointment
          </Button>
        </div>
      )}

      {!outcome && action === 'reschedule' && (
        <div className="mt-6 border-t border-border pt-5">
          <h4 className="font-bold text-ink">Reschedule this appointment</h4>
          <p className="mt-1 text-sm text-muted">
            Choose another time with the same provider and appointment type.
          </p>
          <div className="mt-4">
            <FormField
              type="date"
              label="New appointment date"
              value={rescheduleDate}
              onChange={(event) => {
                setRescheduleDate(event.target.value)
                setSelectedSlot(null)
                setError(null)
              }}
              required
            />
          </div>
          {rescheduleDate && (
            <AvailabilitySlotPicker
              providerId={appointment.providerId}
              appointmentTypeId={appointment.appointmentTypeId}
              date={rescheduleDate}
              selectedSlot={selectedSlot}
              onSelectSlot={(slot) => {
                setSelectedSlot(slot)
                setError(null)
              }}
            />
          )}
          <div className="mt-5 flex flex-wrap gap-3">
            <Button
              type="button"
              disabled={!selectedSlot}
              loading={submitting}
              loadingText="Rescheduling..."
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

      {!outcome && action === 'cancel' && (
        <div className="mt-6 border-t border-border pt-5">
          <h4 className="font-bold text-ink">Cancel this appointment?</h4>
          <div className="mt-4">
            <FormField
              label="Reason (optional)"
              placeholder="Provider unavailable"
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

      {error && (
        <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
          {error.message}
        </p>
      )}
    </Card>
  )
}

type ConflictResolutionProps = {
  block: ProviderUnavailabilityResponse
  onActivated: () => void
  onCancelled: () => void
}

function ConflictResolution({
  block,
  onActivated,
  onCancelled,
}: ConflictResolutionProps) {
  const [appointments, setAppointments] = useState<AppointmentResponse[]>([])
  const [loadingAppointments, setLoadingAppointments] = useState(true)
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [outcomes, setOutcomes] = useState<Record<number, ConflictOutcome | undefined>>({})
  const [activating, setActivating] = useState(false)
  const [activationError, setActivationError] = useState<ApiError | null>(null)
  const [confirmingBlockCancellation, setConfirmingBlockCancellation] = useState(false)
  const [cancellingBlock, setCancellingBlock] = useState(false)
  const [blockCancellationError, setBlockCancellationError] = useState<ApiError | null>(null)

  useEffect(() => {
    let active = true
    setAppointments([])
    setLoadingAppointments(true)
    setLoadError(null)

    void providerBlockingApi.findConflictingAppointments(block.conflictingAppointmentIds)
      .then((response) => {
        if (active) setAppointments(response)
      })
      .catch((error: unknown) => {
        if (active) setLoadError(normalizedError(error))
      })
      .finally(() => {
        if (active) setLoadingAppointments(false)
      })

    return () => { active = false }
  }, [block])

  async function activateBlock() {
    setActivating(true)
    setActivationError(null)
    try {
      await providerBlockingApi.activate(block.id)
      onActivated()
    } catch (error) {
      setActivationError(normalizedError(error))
    } finally {
      setActivating(false)
    }
  }

  async function cancelBlock() {
    setCancellingBlock(true)
    setBlockCancellationError(null)
    try {
      await providerBlockingApi.cancel(block.id)
      onCancelled()
    } catch (error) {
      setBlockCancellationError(normalizedError(error))
    } finally {
      setCancellingBlock(false)
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
          Conflict resolution
        </p>
        <h2 className="text-2xl font-bold text-ink">Resolve conflicting appointments</h2>
        <p className="mt-3 leading-7 text-muted">
          This block conflicts with {block.conflictingAppointmentIds.length} appointment(s):
        </p>
        <p className="mt-2 text-sm text-muted">
          Reschedule or cancel each appointment, then activate the block. You can try activation
          at any time; the server will confirm whether conflicts remain.
        </p>
      </Card>

      {loadingAppointments && (
        <p className="text-sm font-medium text-muted" role="status">
          Loading conflicting appointments...
        </p>
      )}
      {loadError && (
        <p className="rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
          {loadError.message}
        </p>
      )}
      {appointments.map((appointment) => (
        <ConflictAppointment
          key={appointment.id}
          appointment={appointment}
          outcome={outcomes[appointment.id]}
          onResolved={(outcome) => setOutcomes((current) => ({
            ...current,
            [appointment.id]: outcome,
          }))}
        />
      ))}

      <Card>
        <h3 className="text-lg font-bold text-ink">Finish the time block</h3>
        <p className="mt-2 text-sm leading-6 text-muted">
          Activation is checked against the backend's current appointment state.
        </p>
        <div className="mt-5 flex flex-wrap gap-3">
          <Button
            type="button"
            loading={activating}
            loadingText="Activating..."
            onClick={() => void activateBlock()}
          >
            Activate this time block
          </Button>
          {!confirmingBlockCancellation && (
            <Button
              type="button"
              variant="danger"
              onClick={() => {
                setConfirmingBlockCancellation(true)
                setBlockCancellationError(null)
              }}
            >
              Don't create this time block
            </Button>
          )}
        </div>

        {confirmingBlockCancellation && (
          <div className="mt-5 border-t border-border pt-5">
            <p className="font-bold text-ink">Cancel this pending time block?</p>
            <p className="mt-1 text-sm text-muted">
              The provider time block will not be created.
            </p>
            <div className="mt-4 flex flex-wrap gap-3">
              <Button
                type="button"
                variant="danger"
                loading={cancellingBlock}
                loadingText="Cancelling block..."
                onClick={() => void cancelBlock()}
              >
                Confirm: don't create this time block
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={cancellingBlock}
                onClick={() => setConfirmingBlockCancellation(false)}
              >
                Back
              </Button>
            </div>
          </div>
        )}

        {activationError && (
          <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
            {activationError.message}
          </p>
        )}
        {blockCancellationError && (
          <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
            {blockCancellationError.message}
          </p>
        )}
      </Card>
    </div>
  )
}

export function ProviderBlockingPage() {
  const providersQuery = useApiQuery<ProviderListResponse[]>('/api/providers', [])
  const [providerId, setProviderId] = useState('')
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [reason, setReason] = useState('')
  const [creating, setCreating] = useState(false)
  const [creationError, setCreationError] = useState<ApiError | null>(null)
  const [pendingBlock, setPendingBlock] = useState<ProviderUnavailabilityResponse | null>(null)
  const [confirmation, setConfirmation] = useState<string | null>(null)

  function resetForm() {
    setProviderId('')
    setStartAt('')
    setEndAt('')
    setReason('')
  }

  async function createBlock(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!providerId || !startAt || !endAt) return

    setCreating(true)
    setCreationError(null)
    setConfirmation(null)
    try {
      const trimmedReason = reason.trim()
      const block = await providerBlockingApi.create({
        providerId: Number(providerId),
        startAt: localDateTimeToInstant(startAt),
        endAt: localDateTimeToInstant(endAt),
        ...(trimmedReason ? { reason: trimmedReason } : {}),
      })
      if (block.status === 'PENDING') {
        setPendingBlock(block)
      } else {
        resetForm()
        setConfirmation('Time block created and active.')
      }
    } catch (error) {
      setCreationError(normalizedError(error))
    } finally {
      setCreating(false)
    }
  }

  return (
    <PageLayout>
      <div className="mx-auto max-w-4xl py-4 sm:py-8">
        <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
          Provider scheduling
        </p>
        <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
          Block provider time
        </h1>
        <p className="mt-3 max-w-2xl leading-7 text-muted">
          Create a provider time block and resolve any appointments that overlap it.
        </p>

        {confirmation && (
          <p className="mt-6 rounded-xl border border-brand/20 bg-brand-soft px-4 py-3 font-semibold text-brand-deep" role="status">
            {confirmation}
          </p>
        )}

        {pendingBlock ? (
          <div className="mt-8">
            <ConflictResolution
              key={pendingBlock.id}
              block={pendingBlock}
              onActivated={() => {
                setPendingBlock(null)
                resetForm()
                setConfirmation('Time block is now active.')
              }}
              onCancelled={() => {
                setPendingBlock(null)
                resetForm()
                setConfirmation('Time block cancelled.')
              }}
            />
          </div>
        ) : (
          <Card className="mt-8">
            <form className="space-y-5" onSubmit={(event) => void createBlock(event)}>
              <div>
                <label className="block text-sm font-semibold text-ink" htmlFor="block-provider">
                  Provider
                </label>
                <select
                  id="block-provider"
                  className="mt-2 block min-h-12 w-full rounded-xl border border-border bg-surface px-3.5 py-2.5 text-base text-ink shadow-xs outline-none transition focus:border-brand focus:ring-4 focus:ring-brand/15"
                  value={providerId}
                  onChange={(event) => setProviderId(event.target.value)}
                  disabled={providersQuery.loading}
                  required
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

              <div className="grid gap-5 sm:grid-cols-2">
                <FormField
                  type="datetime-local"
                  label="Start date and time"
                  value={startAt}
                  onChange={(event) => setStartAt(event.target.value)}
                  required
                />
                <FormField
                  type="datetime-local"
                  label="End date and time"
                  value={endAt}
                  onChange={(event) => setEndAt(event.target.value)}
                  required
                />
              </div>

              <FormField
                label="Reason (optional)"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />

              <Button
                type="submit"
                loading={creating}
                loadingText="Creating block..."
                disabled={providersQuery.loading || !providerId || !startAt || !endAt}
              >
                Create time block
              </Button>

              {creationError && (
                <p className="rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
                  {creationError.message}
                </p>
              )}
            </form>
          </Card>
        )}
      </div>
    </PageLayout>
  )
}