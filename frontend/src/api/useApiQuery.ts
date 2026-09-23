/* oxlint-disable react/set-state-in-effect, react-hooks/exhaustive-deps */
import { useCallback, useEffect, useState } from 'react'
import { apiRequest, isApiError } from './client'
import type { ApiError } from './types'

type ApiQueryResult<T> = {
  data: T | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

function queryError(error: unknown): ApiError {
  return isApiError(error)
    ? error
    : { message: 'An unexpected error prevented the request from completing.' }
}

export function useApiQuery<T>(
  path: `/api/${string}` | null,
  deps: readonly unknown[],
): ApiQueryResult<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [requestVersion, setRequestVersion] = useState(0)

  const refetch = useCallback(() => {
    setRequestVersion((version) => version + 1)
  }, [])

  useEffect(() => {
    let active = true

    if (path === null) {
      setData(null)
      setLoading(false)
      setError(null)
      return () => { active = false }
    }

    setData(null)
    setLoading(true)
    setError(null)
    void apiRequest<T>(path)
      .then((response) => {
        if (active) setData(response)
      })
      .catch((requestError: unknown) => {
        if (active) setError(queryError(requestError))
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => { active = false }
  }, [path, requestVersion, ...deps])

  return { data, loading, error, refetch }
}
