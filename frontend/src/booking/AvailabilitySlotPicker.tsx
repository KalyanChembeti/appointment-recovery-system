import type { TimeSlot } from '../api/types'
import { useApiQuery } from '../api/useApiQuery'
import { Button } from '../components/Button'

type AvailabilitySlotPickerProps = {
  providerId: number
  appointmentTypeId: number
  date: string
  selectedSlot: TimeSlot | null
  onSelectSlot: (slot: TimeSlot) => void
}

function formatTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(new Date(instant))
}

function formatDateTime(instant: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(instant))
}

export function AvailabilitySlotPicker({
  providerId,
  appointmentTypeId,
  date,
  selectedSlot,
  onSelectSlot,
}: AvailabilitySlotPickerProps) {
  const path: `/api/${string}` = `/api/availability?providerId=${encodeURIComponent(providerId)}&appointmentTypeId=${encodeURIComponent(appointmentTypeId)}&date=${encodeURIComponent(date)}`
  const availabilityQuery = useApiQuery<TimeSlot[]>(path, [
    providerId,
    appointmentTypeId,
    date,
  ])

  return (
    <>
      {availabilityQuery.loading && (
        <p className="mt-5 text-sm font-medium text-muted" role="status">
          Loading available times...
        </p>
      )}
      {availabilityQuery.error && (
        <p className="mt-5 text-sm font-medium text-danger" role="alert">
          {availabilityQuery.error.message}
        </p>
      )}
      {availabilityQuery.data?.length === 0 && (
        <p className="mt-5 rounded-xl bg-soft px-4 py-3 text-sm font-medium text-muted">
          No available times for this date.
        </p>
      )}
      {availabilityQuery.data && availabilityQuery.data.length > 0 && (
        <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3">
          {availabilityQuery.data.map((slot) => {
            const selected = selectedSlot?.startAt === slot.startAt
            return (
              <Button
                key={slot.startAt}
                type="button"
                variant="secondary"
                aria-label={`Select ${formatDateTime(slot.startAt)}`}
                aria-pressed={selected}
                className={selected ? 'ring-2 ring-brand ring-offset-2' : ''}
                onClick={() => onSelectSlot(slot)}
              >
                {formatTime(slot.startAt)}
              </Button>
            )
          })}
        </div>
      )}
    </>
  )
}
