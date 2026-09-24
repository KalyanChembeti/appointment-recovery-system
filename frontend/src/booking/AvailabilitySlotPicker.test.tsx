import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TimeSlot } from '../api/types'
import { AvailabilitySlotPicker } from './AvailabilitySlotPicker'

const SLOT: TimeSlot = {
  startAt: '2040-02-10T15:00:00Z',
  endAt: '2040-02-10T15:30:00Z',
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('AvailabilitySlotPicker', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('fetches the scoped availability and returns the selected slot', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([SLOT]))
    const onSelectSlot = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <AvailabilitySlotPicker
        providerId={21}
        appointmentTypeId={11}
        date="2040-02-10"
        selectedSlot={null}
        onSelectSlot={onSelectSlot}
      />,
    )

    await user.click(await screen.findByRole('button', { name: /^Select / }))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/availability?providerId=21&appointmentTypeId=11&date=2040-02-10',
      expect.any(Object),
    )
    expect(onSelectSlot).toHaveBeenCalledWith(SLOT)
  })
})
