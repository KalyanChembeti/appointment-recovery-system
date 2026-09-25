import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PatientSearchResponse } from '../api/types'
import { PatientSearchPanel } from './PatientSearchPanel'

const PATIENTS: PatientSearchResponse[] = [
  { id: 71, email: 'ava.morgan@example.com', displayName: 'Ava Morgan' },
  { id: 72, email: 'unnamed@example.com', displayName: null },
]

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('PatientSearchPanel', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows a minimum-length hint without submitting a request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<PatientSearchPanel onSelectPatient={vi.fn()} />)

    await user.type(screen.getByLabelText('Patient email or name'), 'a')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Enter at least 2 characters')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('displays successful results and passes the selected patient to its callback', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(PATIENTS))
    vi.stubGlobal('fetch', fetchMock)
    const onSelectPatient = vi.fn()
    const user = userEvent.setup()
    render(<PatientSearchPanel onSelectPatient={onSelectPatient} />)

    await user.type(screen.getByLabelText('Patient email or name'), 'AvA Morgan')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('ava.morgan@example.com')).toBeInTheDocument()
    expect(screen.getByText('Ava Morgan')).toBeInTheDocument()
    expect(screen.getByText('unnamed@example.com')).toBeInTheDocument()
    expect(screen.queryByText('Patient #72')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/patients?query=AvA%20Morgan',
      expect.any(Object),
    )

    await user.click(screen.getByRole('button', { name: /ava\.morgan@example\.com/i }))
    expect(onSelectPatient).toHaveBeenCalledWith(PATIENTS[0])
  })

  it('shows a clear message when a successful search has no results', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const user = userEvent.setup()
    render(<PatientSearchPanel onSelectPatient={vi.fn()} />)

    await user.type(screen.getByLabelText('Patient email or name'), 'missing')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => {
      expect(screen.getByText('No patients found')).toBeInTheDocument()
    })
  })
})
