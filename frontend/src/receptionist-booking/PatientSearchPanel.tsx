import { useState, type FormEvent } from 'react'
import { isApiError } from '../api/client'
import type { ApiError, PatientSearchResponse } from '../api/types'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { patientSearchApi } from './patientSearchApi'

type PatientSearchPanelProps = {
  onSelectPatient: (patient: PatientSearchResponse) => void
}

function normalizedError(error: unknown): ApiError {
  return isApiError(error)
    ? error
    : { message: 'An unexpected error prevented the request from completing.' }
}

export function PatientSearchPanel({ onSelectPatient }: PatientSearchPanelProps) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<PatientSearchResponse[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [validationMessage, setValidationMessage] = useState<string | undefined>()
  const [searchError, setSearchError] = useState<ApiError | null>(null)

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedQuery = query.trim()
    if (normalizedQuery.length < 2) {
      setValidationMessage('Enter at least 2 characters')
      return
    }

    setSearching(true)
    setValidationMessage(undefined)
    setSearchError(null)
    try {
      setResults(await patientSearchApi.search(normalizedQuery))
    } catch (error) {
      setResults(null)
      setSearchError(normalizedError(error))
    } finally {
      setSearching(false)
    }
  }

  return (
    <Card>
      <h2 className="text-xl font-bold text-ink">Find a patient</h2>
      <p className="mt-1 text-sm leading-6 text-muted">
        Search by email or display name.
      </p>
      <form className="mt-5" onSubmit={(event) => void search(event)}>
        <FormField
          label="Patient email or name"
          value={query}
          error={validationMessage}
          onChange={(event) => {
            setQuery(event.target.value)
            setValidationMessage(undefined)
          }}
        />
        <Button
          className="mt-4"
          type="submit"
          loading={searching}
          loadingText="Searching..."
        >
          Search
        </Button>
      </form>

      {searchError && (
        <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
          {searchError.message}
        </p>
      )}

      {results?.length === 0 && (
        <p className="mt-5 rounded-xl bg-soft px-4 py-3 text-sm font-medium text-muted">
          No patients found
        </p>
      )}

      {results && results.length > 0 && (
        <ul className="mt-5 space-y-3" aria-label="Patient search results">
          {results.map((patient) => (
            <li key={patient.id}>
              <button
                type="button"
                className="flex min-h-14 w-full flex-col items-start justify-center rounded-xl border border-border bg-surface px-4 py-3 text-left transition-colors hover:bg-brand-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/25"
                onClick={() => onSelectPatient(patient)}
              >
                <span className="font-semibold text-ink">{patient.email}</span>
                {patient.displayName && (
                  <span className="mt-0.5 text-sm text-muted">{patient.displayName}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
