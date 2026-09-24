import { useState } from 'react'
import { Link } from 'react-router-dom'
import { isApiError } from '../api/client'
import type {
  AcceptOfferResponse,
  ApiError,
  AppointmentTypeResponse,
  ProviderListResponse,
  SlotOfferResponse,
  SlotOfferStatus,
} from '../api/types'
import { useApiQuery } from '../api/useApiQuery'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { PageLayout } from '../components/PageLayout'
import { offersApi } from './offersApi'

const ALREADY_RESOLVED_CODES = new Set([
  'OFFER_ALREADY_ACCEPTED',
  'OFFER_ALREADY_RESOLVED',
])
const OFFER_UNAVAILABLE_MESSAGE = 'This offer is no longer available.'
const OFFER_EXPIRED_MESSAGE = 'This offer has expired.'

const STATUS_CLASSES: Record<SlotOfferStatus, string> = {
  OFFERED: 'bg-brand-soft text-brand-deep',
  ACCEPTED: 'bg-brand/10 text-brand-deep',
  DECLINED: 'bg-danger/10 text-danger',
  EXPIRED: 'bg-soft text-muted',
  CANCELLED: 'bg-danger/10 text-danger',
}

type OfferFeedback =
  | { kind: 'success'; appointment: AcceptOfferResponse }
  | { kind: 'error'; error: ApiError }

function formatDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

function formatTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
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

function statusLabel(status: SlotOfferStatus) {
  return status.replaceAll('_', ' ').toLowerCase()
}

type OfferEntryProps = {
  offer: SlotOfferResponse
  providerName: string
  appointmentTypeName: string
  providersById: Map<number, ProviderListResponse>
  feedback?: OfferFeedback
  onFeedback: (feedback?: OfferFeedback) => void
  onListChanged: () => void
}

function OfferEntry({
  offer,
  providerName,
  appointmentTypeName,
  providersById,
  feedback,
  onFeedback,
  onListChanged,
}: OfferEntryProps) {
  const [confirmingDecline, setConfirmingDecline] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [declining, setDeclining] = useState(false)
  const [declineError, setDeclineError] = useState<ApiError | null>(null)
  const offered = offer.status === 'OFFERED'

  async function acceptOffer() {
    setAccepting(true)
    setDeclineError(null)
    onFeedback(undefined)
    try {
      const appointment = await offersApi.accept(offer.id)
      onFeedback({ kind: 'success', appointment })
      onListChanged()
    } catch (error) {
      const apiError = normalizedError(error)
      if (
        apiError.status === 409
        && apiError.code
        && ALREADY_RESOLVED_CODES.has(apiError.code)
      ) {
        onFeedback({
          kind: 'error',
          error: { ...apiError, message: OFFER_UNAVAILABLE_MESSAGE },
        })
        onListChanged()
      } else if (apiError.status === 410 && apiError.code === 'OFFER_EXPIRED') {
        onFeedback({
          kind: 'error',
          error: { ...apiError, message: OFFER_EXPIRED_MESSAGE },
        })
        onListChanged()
      } else {
        onFeedback({ kind: 'error', error: apiError })
      }
    } finally {
      setAccepting(false)
    }
  }

  async function declineOffer() {
    setDeclining(true)
    setDeclineError(null)
    try {
      await offersApi.decline(offer.id)
      setConfirmingDecline(false)
      onListChanged()
    } catch (error) {
      setDeclineError(normalizedError(error))
    } finally {
      setDeclining(false)
    }
  }

  const acceptedAppointment = feedback?.kind === 'success'
    ? feedback.appointment
    : null

  return (
    <Card
      role="article"
      aria-labelledby={`offer-${offer.id}-heading`}
      className="p-5 sm:p-6"
    >
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div>
          <h3 id={`offer-${offer.id}-heading`} className="text-lg font-bold text-ink">
            {appointmentTypeName}
          </h3>
          <p className="mt-1 font-medium text-muted">{providerName}</p>
          <p className="mt-3 text-lg font-bold text-ink">
            {formatDateTime(offer.startAt)} – {formatTime(offer.endAt)}
          </p>
          {offered && (
            <p className="mt-2 text-sm font-semibold text-muted">
              Expires {formatDateTime(offer.expiresAt)}
            </p>
          )}
        </div>
        <span
          className={`w-fit rounded-full px-3 py-1 text-xs font-bold uppercase tracking-wide ${STATUS_CLASSES[offer.status]}`}
        >
          {statusLabel(offer.status)}
        </span>
      </div>

      {offered && !confirmingDecline && (
        <div className="mt-5 flex flex-wrap gap-3">
          <Button
            type="button"
            loading={accepting}
            loadingText="Accepting..."
            disabled={declining}
            onClick={() => void acceptOffer()}
          >
            Accept
          </Button>
          <Button
            type="button"
            variant="secondary"
            disabled={accepting}
            onClick={() => {
              setConfirmingDecline(true)
              setDeclineError(null)
              onFeedback(undefined)
            }}
          >
            Decline
          </Button>
        </div>
      )}

      {offered && confirmingDecline && (
        <div className="mt-6 border-t border-border pt-5">
          <h4 className="font-bold text-ink">Decline this offer?</h4>
          <div className="mt-4 flex flex-wrap gap-3">
            <Button
              type="button"
              variant="danger"
              loading={declining}
              loadingText="Declining..."
              onClick={() => void declineOffer()}
            >
              Confirm decline
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={declining}
              onClick={() => {
                setConfirmingDecline(false)
                setDeclineError(null)
              }}
            >
              Back
            </Button>
          </div>
        </div>
      )}

      {acceptedAppointment && (
        <div
          className="mt-5 rounded-xl border border-brand/20 bg-brand-soft px-4 py-4 text-brand-deep"
          role="status"
        >
          <p className="font-bold">Offer accepted</p>
          <p className="mt-1 text-sm font-medium">
            Appointment #{acceptedAppointment.appointmentId} with{' '}
            {providerLabel(
              providersById.get(acceptedAppointment.providerId),
              acceptedAppointment.providerId,
            )} is confirmed for {formatDateTime(acceptedAppointment.startAt)} –{' '}
            {formatTime(acceptedAppointment.endAt)}.
          </p>
          <Link
            className="mt-3 inline-flex text-sm font-bold underline underline-offset-4"
            to="/appointments"
          >
            View my appointments
          </Link>
        </div>
      )}

      {feedback?.kind === 'error' && (
        <p
          className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger"
          role="alert"
        >
          {feedback.error.message}
        </p>
      )}

      {declineError && (
        <p
          className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger"
          role="alert"
        >
          {declineError.message}
        </p>
      )}
    </Card>
  )
}

export function MyOffersPage() {
  const offersQuery = useApiQuery<SlotOfferResponse[]>('/api/slot-offers', [])
  const providersQuery = useApiQuery<ProviderListResponse[]>('/api/providers', [])
  const appointmentTypesQuery = useApiQuery<AppointmentTypeResponse[]>(
    '/api/appointment-types',
    [],
  )
  const [feedbackByOfferId, setFeedbackByOfferId] = useState<
    Record<number, OfferFeedback | undefined>
  >({})

  const providersById = new Map(
    providersQuery.data?.map((provider) => [provider.id, provider]) ?? [],
  )
  const appointmentTypesById = new Map(
    appointmentTypesQuery.data?.map((appointmentType) => [appointmentType.id, appointmentType])
      ?? [],
  )
  const active = (offersQuery.data ?? [])
    .filter((offer) => offer.status === 'OFFERED')
    .sort((first, second) => Date.parse(first.expiresAt) - Date.parse(second.expiresAt))
  const resolved = (offersQuery.data ?? [])
    .filter((offer) => offer.status !== 'OFFERED')
    .sort((first, second) => second.id - first.id)
  const loading = offersQuery.loading
    || providersQuery.loading
    || appointmentTypesQuery.loading
  const pageErrors = [
    offersQuery.error,
    providersQuery.error,
    appointmentTypesQuery.error,
  ].filter((error): error is ApiError => error !== null)

  function entry(offer: SlotOfferResponse) {
    return (
      <OfferEntry
        key={offer.id}
        offer={offer}
        providerName={providerLabel(providersById.get(offer.providerId), offer.providerId)}
        appointmentTypeName={appointmentTypeLabel(
          appointmentTypesById.get(offer.appointmentTypeId),
          offer.appointmentTypeId,
        )}
        providersById={providersById}
        feedback={feedbackByOfferId[offer.id]}
        onFeedback={(feedback) => setFeedbackByOfferId((current) => ({
          ...current,
          [offer.id]: feedback,
        }))}
        onListChanged={offersQuery.refetch}
      />
    )
  }

  return (
    <PageLayout>
      <div className="mx-auto max-w-4xl py-4 sm:py-8">
        <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
          Appointment recovery
        </p>
        <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
          My offers
        </h1>
        <p className="mt-3 leading-7 text-muted">
          Review available earlier appointments and your resolved offers.
        </p>

        {loading && (
          <p className="mt-8 text-sm font-medium text-muted" role="status">
            Loading offers...
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
            <section aria-labelledby="active-offers-heading">
              <h2 id="active-offers-heading" className="text-2xl font-bold text-ink">
                Active
              </h2>
              <div className="mt-4 space-y-4">
                {active.length > 0
                  ? active.map(entry)
                  : <p className="text-sm text-muted">No active offers.</p>}
              </div>
            </section>

            <section aria-labelledby="resolved-offers-heading">
              <h2 id="resolved-offers-heading" className="text-2xl font-bold text-ink">
                Resolved
              </h2>
              <div className="mt-4 space-y-4">
                {resolved.length > 0
                  ? resolved.map(entry)
                  : <p className="text-sm text-muted">No resolved offers.</p>}
              </div>
            </section>
          </div>
        )}
      </div>
    </PageLayout>
  )
}
