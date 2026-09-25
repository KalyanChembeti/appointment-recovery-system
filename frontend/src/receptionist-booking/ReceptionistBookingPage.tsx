import { useState } from 'react'
import type { AppointmentResponse, PatientSearchResponse } from '../api/types'
import { AppointmentBookingForm } from '../booking/AppointmentBookingForm'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { PageLayout } from '../components/PageLayout'
import { PatientSearchPanel } from './PatientSearchPanel'

function PatientIdentity({ patient }: { patient: PatientSearchResponse }) {
  return (
    <div>
      <p className="font-semibold text-ink">{patient.email}</p>
      {patient.displayName && (
        <p className="mt-1 text-sm text-muted">{patient.displayName}</p>
      )}
    </div>
  )
}

export function ReceptionistBookingPage() {
  const [selectedPatient, setSelectedPatient] = useState<PatientSearchResponse | null>(null)
  const [bookedAppointment, setBookedAppointment] = useState<AppointmentResponse | null>(null)

  function resetToSearch() {
    setSelectedPatient(null)
    setBookedAppointment(null)
  }

  if (bookedAppointment && selectedPatient) {
    return (
      <PageLayout>
        <div className="mx-auto max-w-2xl py-6 sm:py-10">
          <Card>
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">
              Appointment confirmed
            </p>
            <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
              Appointment booked
            </h1>
            <p className="mt-3 leading-6 text-muted">
              The appointment was booked for:
            </p>
            <div className="mt-4 rounded-xl bg-soft p-4">
              <PatientIdentity patient={selectedPatient} />
            </div>
            <p className="mt-5 text-sm font-semibold text-ink">
              Appointment #{bookedAppointment.id} · {bookedAppointment.status.toLowerCase()}
            </p>
            <Button className="mt-6" type="button" onClick={resetToSearch}>
              Book another appointment
            </Button>
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
            Staff scheduling
          </p>
          <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">
            Book for a patient
          </h1>
          <p className="mt-3 max-w-2xl leading-7 text-muted">
            Find a patient, then choose their appointment details.
          </p>
        </div>

        {!selectedPatient ? (
          <PatientSearchPanel onSelectPatient={setSelectedPatient} />
        ) : (
          <div className="space-y-6">
            <Card>
              <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                <div>
                  <p className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">
                    Selected patient
                  </p>
                  <PatientIdentity patient={selectedPatient} />
                </div>
                <Button type="button" variant="secondary" onClick={resetToSearch}>
                  Change patient
                </Button>
              </div>
            </Card>
            <AppointmentBookingForm
              patientId={selectedPatient.id}
              onBooked={setBookedAppointment}
            />
          </div>
        )}
      </div>
    </PageLayout>
  )
}
