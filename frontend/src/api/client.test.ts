import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function clearCookies() {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
}

describe('apiRequest', () => {
  beforeEach(clearCookies)
  afterEach(() => {
    vi.unstubAllGlobals()
    clearCookies()
  })

  it('attaches the X-XSRF-TOKEN header to a POST when the cookie is present', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token-123; Path=/'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example', { method: 'POST' })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    const csrfHeader = new Headers(init.headers).get('X-XSRF-TOKEN')
    expect(csrfHeader).toBe('csrf-token-123')
  })

  it('bootstraps once before a mutation and reuses the raw cookie afterward', async () => {
    const fetchMock = vi.fn().mockImplementation((path: string) => {
      if (path === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=bootstrapped-token; Path=/'
        return Promise.resolve(jsonResponse({ status: 'ready' }))
      }
      return Promise.resolve(jsonResponse({ accepted: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example', { method: 'POST' })
    await apiRequest('/api/example', { method: 'POST' })

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/csrf',
      '/api/example',
      '/api/example',
    ])
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get('X-XSRF-TOKEN'))
      .toBe('bootstrapped-token')
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get('X-XSRF-TOKEN'))
      .toBe('bootstrapped-token')
  })

  it('never attaches a CSRF header to a GET, even when the cookie is present', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token-123; Path=/'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ value: true }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example')

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).has('X-XSRF-TOKEN')).toBe(false)
  })

  it('includes credentials on safe, bootstrap, and state-changing requests', async () => {
    const fetchMock = vi.fn().mockImplementation((path: string) => {
      if (path === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=bootstrapped-token; Path=/'
        return Promise.resolve(jsonResponse({ status: 'ready' }))
      }
      return Promise.resolve(jsonResponse({ value: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example')
    await apiRequest('/api/example', { method: 'POST' })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    fetchMock.mock.calls.forEach(([, init]) => {
      expect((init as RequestInit).credentials).toBe('include')
    })
  })

  it.each([
    {
      name: 'standard ApiErrorResponse',
      body: {
        code: 'APPOINTMENT_OWNERSHIP',
        message: 'Appointment belongs to another patient',
        timestamp: '2026-09-21T12:00:00Z',
        path: '/api/appointments/1/cancel',
      },
      expected: {
        status: 400,
        code: 'APPOINTMENT_OWNERSHIP',
        message: 'Appointment belongs to another patient',
        timestamp: '2026-09-21T12:00:00Z',
        path: '/api/appointments/1/cancel',
        errors: undefined,
      },
    },
    {
      name: 'BadCredentials response',
      body: { message: 'Invalid email or password' },
      expected: {
        status: 400,
        code: undefined,
        message: 'Invalid email or password',
        timestamp: undefined,
        path: undefined,
        errors: undefined,
      },
    },
    {
      name: 'validation response',
      body: {
        message: 'Validation failed',
        errors: { email: 'must be a well-formed email address' },
      },
      expected: {
        status: 400,
        code: undefined,
        message: 'Validation failed',
        timestamp: undefined,
        path: undefined,
        errors: { email: 'must be a well-formed email address' },
      },
    },
  ])('normalizes the $name into ApiError', async ({ body, expected }) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body, 400)))
    await expect(apiRequest('/api/example')).rejects.toEqual(expected)
  })
})
