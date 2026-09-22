import type { ApiError } from './types'

const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

function readCookie(name: string): string | undefined {
  const prefix = `${encodeURIComponent(name)}=`
  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined
}

async function bootstrapCsrfCookie(): Promise<void> {
  await apiRequest<{ status: string }>('/api/auth/csrf')
}

function stringProperty(value: Record<string, unknown>, key: string) {
  return typeof value[key] === 'string' ? (value[key] as string) : undefined
}

function errorMap(value: unknown): Record<string, string> | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined
  }
  const entries = Object.entries(value).filter(
    (entry): entry is [string, string] => typeof entry[1] === 'string',
  )
  return entries.length > 0 ? Object.fromEntries(entries) : undefined
}

async function normalizeError(response: Response): Promise<ApiError> {
  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = undefined
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return {
      status: response.status,
      message: response.statusText || `Request failed with status ${response.status}`,
    }
  }
  const responseBody = body as Record<string, unknown>
  return {
    status: response.status,
    code: stringProperty(responseBody, 'code'),
    message:
      stringProperty(responseBody, 'message') ??
      response.statusText ??
      `Request failed with status ${response.status}`,
    timestamp: stringProperty(responseBody, 'timestamp'),
    path: stringProperty(responseBody, 'path'),
    errors: errorMap(responseBody.errors),
  }
}

export function isApiError(value: unknown): value is ApiError {
  return (
    typeof value === 'object' &&
    value !== null &&
    'message' in value &&
    typeof value.message === 'string'
  )
}

export async function apiRequest<T>(
  path: `/api/${string}`,
  init: RequestInit = {},
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)

  if (!SAFE_METHODS.has(method)) {
    let csrfToken = readCookie(CSRF_COOKIE_NAME)
    if (!csrfToken) {
      await bootstrapCsrfCookie()
      csrfToken = readCookie(CSRF_COOKIE_NAME)
    }
    if (!csrfToken) {
      throw {
        code: 'CSRF_BOOTSTRAP_UNAVAILABLE',
        message: 'The backend did not issue the XSRF-TOKEN cookie required for this request.',
      } satisfies ApiError
    }
    headers.set(CSRF_HEADER_NAME, csrfToken)
  }

  const response = await fetch(path, {
    ...init,
    method,
    headers,
    credentials: 'include',
  })
  if (!response.ok) {
    throw await normalizeError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
