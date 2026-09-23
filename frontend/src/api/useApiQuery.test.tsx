import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useApiQuery } from './useApiQuery'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

describe('useApiQuery', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('skips fetching while path is null', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useApiQuery<unknown>(null, []))

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current).toEqual(expect.objectContaining({
      data: null,
      loading: false,
      error: null,
    }))
  })

  it('ignores an earlier response after dependencies change', async () => {
    const firstResponse = deferred<Response>()
    const secondResponse = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(firstResponse.promise)
      .mockReturnValueOnce(secondResponse.promise)
    vi.stubGlobal('fetch', fetchMock)

    const { result, rerender } = renderHook(
      ({ dependency }) => useApiQuery<{ value: string }>('/api/example', [dependency]),
      { initialProps: { dependency: 'first' } },
    )
    rerender({ dependency: 'second' })

    await act(async () => {
      secondResponse.resolve(jsonResponse({ value: 'current' }))
      await secondResponse.promise
    })
    await waitFor(() => expect(result.current.data).toEqual({ value: 'current' }))

    await act(async () => {
      firstResponse.resolve(jsonResponse({ value: 'stale' }))
      await firstResponse.promise
    })

    expect(result.current.data).toEqual({ value: 'current' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
