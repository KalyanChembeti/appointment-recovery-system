import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { AppointmentResponse, ProviderListResponse } from '../api/types'
import { Card } from '../components/Card'
import { PageLayout } from '../components/PageLayout'
import { AppointmentBookingForm } from './AppointmentBookingForm'

function formatDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

function providerLabel(provider: ProviderListResponse) {
  return provider.displayName ?? `Provider #${provider.id}`
}

export function BookingPage() {
  const [bookedAppointment, setBookedAppointment] =
    useState<AppointmentResponse | null>(null)
  const [bookedProviderLabel, setBookedProviderLabel] = useState('')

  if (bookedAppointment) {
    return (
      <PageLayout>
        <div className="mx-auto max-w-2xl py-6 sm:py-10">
          <Card>
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
              Appointment confirmed
            </p>
            <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
              You’re booked
            </h1>
            <p className="mt-3 leading-6 text-muted">
              Your appointment is scheduled and ready.
            </p>
            <dl className="mt-6 grid gap-4 rounded-xl bg-soft p-5 sm:grid-cols-2">
              <div>
                <dt className="text-xs font-bold uppercase tracking-wide text-muted">Appointment</dt>
                <dd className="mt-1 font-semibold text-ink">#{bookedAppointment.id}</dd>
              </div>
              <div>
                <dt className="text-xs font-bold uppercase tracking-wide text-muted">Status</dt>
                <dd className="mt-1 font-semibold capitalize text-ink">
                  {bookedAppointment.status.toLowerCase()}
                </dd>
              </div>
              <div>
                <dt className="text-xs font-bold uppercase tracking-wide text-muted">Provider</dt>
                <dd className="mt-1 font-semibold text-ink">{bookedProviderLabel}</dd>
              </div>
              <div>
                <dt className="text-xs font-bold uppercase tracking-wide text-muted">When</dt>
                <dd className="mt-1 font-semibold text-ink">
                  {formatDateTime(bookedAppointment.startAt)}
                </dd>
              </div>
            </dl>
            <Link
              className="mt-6 inline-flex min-h-11 items-center justify-center rounded-xl bg-accent px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-accent-strong focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/30"
              to="/"
            >
              Back to home
            </Link>
          </Card>
        </div>
      </PageLayout>
    )
  }

  return (
    <PageLayout>
      <div className="mx-auto max-w-3xl py-4 sm:py-8">
        <div className="mb-8">
          <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
            Patient scheduling
          </p>
          <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
            Book an appointment
          </h1>
          <p className="mt-3 max-w-2xl leading-7 text-muted">
            Choose a specialty, visit type, provider, and available time.
          </p>
        </div>

        <AppointmentBookingForm
          onBooked={setBookedAppointment}
          onProviderSelected={(provider) => setBookedProviderLabel(providerLabel(provider))}
        />
      </div>
    </PageLayout>
  )
}
